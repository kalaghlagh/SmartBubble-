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
import com.example.ai.providers.AiProviderType
import com.example.ai.providers.ModelInfo
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
import com.example.data.db.ConversationCleaner
import com.example.data.db.SmartBubbleDatabase
import com.example.offline.RuleBasedFormattingEngine
import com.example.thesis.THESIS_PROPOSAL_CARD
import com.example.thesis.ThesisChatNote
import com.example.thesis.ui.ThesisHost
import com.example.thesis.ThesisController
import com.example.thesis.ThesisMessage
import com.example.thesis.ThesisNoteText
import com.example.thesis.ai.OrchestratorThesisAi
import com.example.thesis.data.ThesisRepository
import com.example.thesis.export.ThesisExport
import com.example.thesis.export.ThesisExporter
import com.example.thesis.export.ThesisPdfRenderer
import com.example.offline.document.OfflineDocumentGenerator
import com.example.offline.document.OfflineFormattingController
import com.example.offline.document.OfflineMessage
import com.example.offline.document.RoomOfflineLibrary
import com.example.offline.document.RoomTemplateRepository
import com.example.offline.document.TemplateMatcher
import com.example.ui.commands.SendResult
import com.example.ui.commands.SlashAction
import com.example.ui.commands.SlashCommand
import com.example.ui.commands.SlashCommands
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ConversationEntity
import com.example.data.model.DocumentEntity
import com.example.data.model.ProviderEntity
import com.example.data.model.TemplateEntity
import com.example.template.analysis.TemplateAnalysisEngine
import com.example.template.storage.TemplateImportException
import com.example.template.storage.TemplateImporter
import com.example.template.storage.TemplateThumbnails
import com.example.template.domain.TemplateProfile
import com.example.template.preview.TemplatePreviewEngine
import com.example.template.preview.TemplatePreviewResult
import com.example.ocr.DocumentOcrEngine
import com.example.ocr.OcrLanguage
import com.example.ocr.OcrUnavailableException
import com.example.ocr.PageImageLoader
import com.example.ocr.ScannedDocumentStructureReconstructor
import com.example.ocr.toScannedPage
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

/** State of the model list shown in the provider dialog. */
data class ModelCatalogUiState(
    val loading: Boolean = false,
    val type: AiProviderType? = null,
    val models: List<ModelInfo>? = null,
    val error: String? = null,
    val unsupported: Boolean = false
)

enum class AppNavTab {
    CHAT,
    /** Thesis Builder: an independent destination with its own screens and back stack. */
    THESIS,
    TEMPLATES,
    LIBRARY,
    SETTINGS
}

class SmartBubbleViewModel(application: Application) : AndroidViewModel(application), ThesisHost {

    private companion object {
        const val TAG = "SmartBubbleViewModel"

        /** orchestratorNote of a Smart Templates clarification question: "smart-clarify|<choices>|<request>". */
        const val CLARIFY_NOTE = "smart-clarify"

        /** DocumentEntity.providerUsed value of documents created by on-device OCR. */
        const val LOCAL_OCR_SOURCE = "OCR"

        /** DocumentEntity.providerUsed of thesis exports (kept out of automatic AI context). */
        const val THESIS_SOURCE = "Thesis Builder"

        /** Cache folder of offline documents waiting for "Save to Library". */
        const val OFFLINE_DRAFTS_DIR = "offline_drafts"
    }

    private val context = application.applicationContext
    val database = SmartBubbleDatabase.getDatabase(context)
    val preferences = PreferencesManager(context)
    val deliveryManager = DeliveryManager(context) { id -> str(id) }
    private val credentialStore = CredentialStore(context)
    /** Provider management + single source of truth for the default provider (Room flag). */
    val providerManager = ProviderManager(database, credentialStore)
    /**
     * Chat turns run through the Agent's sequential provider routing (Settings > Agent model
     * order). Thesis Builder, content commands and OCR enhancement keep using the default provider.
     */
    val orchestrator = CentralAiOrchestrator(context, database, deliveryManager, providerManager,
        defaultClient = { agent.turnClient(onRestart = { streamRestartedOp = chatOperationId; _streamingReply.value = "" }) })

    /** Operation whose streamed text was discarded because another model took over (its partial text is not shown). */
    @Volatile
    private var streamRestartedOp = -1L

    /** History and progress listener of the Agent task step that is generating now. */
    @Volatile
    private var agentTurn: Pair<List<ContextMessage>, AiProgressListener>? = null

    /** Provider attempts of the Agent task running now (every orchestrator turn of it). */
    private val agentAttempts = java.util.Collections.synchronizedList(ArrayList<com.example.agent.routing.ProviderAttempt>())

    /** Agent: routing, tools over existing features, task checkpoints. */
    val agent = com.example.agent.AgentService(
        database = database,
        preferences = preferences,
        providerManager = providerManager,
        extractPdf = { file ->
            com.example.extraction.ExtractionText.plain(
                extractionEngine.extract(
                    listOf(com.example.extraction.ExtractionSource(Uri.fromFile(file), isPdf = true)),
                    OcrLanguage.fromCode(ocrLanguage.value), appLanguage.value
                ) { _, _ -> }
            )
        },
        generate = { _, message, sources, format ->
            val (history, listener) = agentTurn ?: (emptyList<ContextMessage>() to AiProgressListener.NONE)
            val template = _selectedTemplateForChat.value
            val parsedFormat = DocumentFormat.parse(format)
            try {
                val r = orchestrator.handleChatTurn(
                    history = history + sources,
                    userMessage = message,
                    uiLanguage = appLanguage.value,
                    selectedTemplate = template,
                    forceFormat = parsedFormat,
                    listener = listener,
                    // Without a named format the planner decides; Smart Templates detection needs one.
                    smart = if (parsedFormat != null) smartOptions(null, null) else null
                )
                val doc = r.document?.let { attributeToServingModel(it) }
                com.example.agent.GeneratedContent(r.reply, doc, r.templateApplied || r.template != null, template != null)
            } finally {
                agent.currentSession?.attempts?.let { agentAttempts.addAll(it) }
            }
        },
        share = { doc -> shareDocument(doc) }
    )

    /** Smart Templates: the template library shared by Chat, My Templates and Thesis Builder. */
    val smartTemplates = com.example.smarttemplates.SmartTemplateService(database)
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
            if (_voiceConversationActive.value) {
                if (thesis.state.value.chatProjectId != null) {
                    // Thesis mode: the transcription is a thesis instruction (reviewed, not spoken back).
                    endVoiceConversation()
                    sendToThesis(text)
                } else executePrompt(text, fromVoice = true)
            }
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

    // ------------------------------------------------------------------ Smart Templates

    /** Built-in and uploaded templates with their categories, defaults and favorites. */
    val templateLibrary: StateFlow<List<com.example.smarttemplates.LibraryTemplate>> =
        smartTemplates.library().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val autoFormat: StateFlow<Boolean> = preferences.autoFormat.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val documentLanguage: StateFlow<String> = preferences.documentLanguage.stateIn(viewModelScope, SharingStarted.Eagerly, "auto")
    val documentFormat: StateFlow<String> = preferences.documentFormat.stateIn(viewModelScope, SharingStarted.Eagerly, "DOCX")

    fun setAutoFormat(enabled: Boolean) = launchSafely("setAutoFormat") { preferences.setAutoFormat(enabled) }
    fun setDocumentLanguage(language: String) = launchSafely("setDocumentLanguage") { preferences.setDocumentLanguage(language) }
    fun setDocumentFormat(format: String) = launchSafely("setDocumentFormat") { preferences.setDocumentFormat(format) }

    /** Makes [templateId] the default of a subcategory or category (one default per scope). */
    fun setDefaultTemplateFor(scopeId: String, templateId: String) = launchSafely("setDefaultTemplateFor") {
        smartTemplates.setDefault(scopeId, templateId)
        _snackBarMessage.value = str(R.string.st_default_set)
    }

    fun clearDefaultTemplateFor(scopeId: String) = launchSafely("clearDefaultTemplateFor") { smartTemplates.clearDefault(scopeId) }

    fun setTemplateFavorite(templateId: String, favorite: Boolean) = launchSafely("setTemplateFavorite") { smartTemplates.setFavorite(templateId, favorite) }

    /** Category and subcategory of an uploaded template (asked after import, changeable later). */
    fun classifyTemplate(templateId: String, categoryId: String?, subcategoryId: String?) = launchSafely("classifyTemplate") {
        smartTemplates.classify(templateId, categoryId, subcategoryId)
        _pendingTemplateClassification.value = null
    }

    /** An uploaded template waiting for its category (suggested category and subcategory). */
    data class PendingClassification(val template: TemplateEntity, val categoryId: String, val subcategoryId: String?)
    private val _pendingTemplateClassification = MutableStateFlow<PendingClassification?>(null)
    val pendingTemplateClassification: StateFlow<PendingClassification?> = _pendingTemplateClassification.asStateFlow()
    fun dismissTemplateClassification() { _pendingTemplateClassification.value = null }

    /** Renders a Smart Templates document again with another template, for this document only (the default is unchanged). */
    fun changeDocumentTemplate(doc: DocumentEntity, templateId: String) = launchSafely("changeDocumentTemplate") {
        val format = com.example.ai.orchestrator.DocumentFormat.parse(doc.fileType) ?: com.example.ai.orchestrator.DocumentFormat.DOCX
        val newDoc = orchestrator.rerenderDocument(doc, format, templateId, smartTemplates)
        addDocumentChatMessage(newDoc, str(R.string.st_template_changed, templateDisplayName(newDoc.templateId)))
    }

    /** PDF of a Smart Templates document, drawn with its template (saved to the Library). */
    private suspend fun smartPdf(doc: DocumentEntity) {
        val pdf = orchestrator.rerenderDocument(doc, com.example.ai.orchestrator.DocumentFormat.PDF, doc.templateId, smartTemplates)
        addDocumentChatMessage(pdf, str(R.string.st_pdf_ready, pdf.title))
        openDocument(pdf)
    }

    /** Generates the document again from its original request, same kind and template. */
    fun regenerateDocument(doc: DocumentEntity) {
        val request = doc.sourceRequest ?: return
        executePrompt(request, forceFormat = doc.fileType, displayText = str(R.string.st_regenerate_request, request),
            forcedSubcategory = doc.documentKind, overrideTemplateId = doc.templateId)
    }

    /** The user picked a kind of document offered in a clarification question. */
    fun resolveClarification(originalRequest: String, subcategoryId: String) {
        executePrompt(originalRequest, displayText = templateKindName(subcategoryId), forcedSubcategory = subcategoryId)
    }

    /**
     * First page of a sample document drawn with a built-in document template (cached), for the
     * template details; null for spreadsheet and presentation templates.
     */
    suspend fun builtInSamplePage(id: String, widthPx: Int): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        val t = com.example.smarttemplates.BuiltInTemplates.byId(id) ?: return@withContext null
        if (t.kind != com.example.smarttemplates.OutputKind.DOCUMENT) return@withContext null
        val dir = File(context.cacheDir, "template_samples").apply { mkdirs() }
        val pdf = File(dir, "${t.id}-${appLanguage.value}.pdf")
        try {
            if (!pdf.isFile) com.example.document.pdf.PdfEngine().generatePdf(
                com.example.smarttemplates.SampleContent.word(t.categoryId, str(SmartTemplateStrings.builtInName(t.id))), pdf, t.profile)
            TemplateThumbnails.renderPage(pdf, 0, widthPx)
        } catch (e: Exception) {
            Log.w(TAG, "Sample page failed for ${t.id}", e)
            pdf.delete()
            null
        }
    }

    /** Writes a sample file in the template's own format (DOCX, XLSX or PPTX) and opens it. */
    fun openBuiltInSample(id: String) = launchSafely("openBuiltInSample") {
        val t = com.example.smarttemplates.BuiltInTemplates.byId(id) ?: return@launchSafely
        val name = str(SmartTemplateStrings.builtInName(t.id))
        val dir = File(context.cacheDir, "pdf_exports").apply { mkdirs() }
        val (file, mime) = withContext(Dispatchers.IO) {
            val base = DocumentFileNames.sanitizeBaseName(name)
            when (t.kind) {
                com.example.smarttemplates.OutputKind.SPREADSHEET -> File(dir, "$base.xlsx").also {
                    com.example.document.xlsx.XlsxEngine().generateXlsx(com.example.smarttemplates.SampleContent.sheet(name), it, t.profile) } to DeliveryManager.MIME_XLSX
                com.example.smarttemplates.OutputKind.PRESENTATION -> File(dir, "$base.pptx").also {
                    com.example.document.pptx.PptxEngine().generatePptx(com.example.smarttemplates.SampleContent.slides(name), it, t.profile) } to DeliveryManager.MIME_PPTX
                com.example.smarttemplates.OutputKind.DOCUMENT -> File(dir, "$base.docx").also {
                    com.example.document.docx.DocxEngine().generateDocx(com.example.smarttemplates.SampleContent.word(t.categoryId, name), it, t.profile) } to DeliveryManager.MIME_DOCX
            }
        }
        deliveryManager.openFile(file, mime)
    }

    /** Localized display name of a template id (built-in or uploaded). */
    fun templateDisplayName(id: String?): String {
        if (id == null) return str(R.string.st_neutral_formatting)
        com.example.smarttemplates.BuiltInTemplates.byId(id)?.let { return str(SmartTemplateStrings.builtInName(it.id)) }
        return templateLibrary.value.firstOrNull { it.id == id }?.uploaded?.name ?: id
    }

    fun templateKindName(subcategoryId: String): String = str(SmartTemplateStrings.subcategory(subcategoryId))

    private suspend fun addDocumentChatMessage(doc: DocumentEntity, text: String) {
        val sessionId = _currentSessionId.value
        if (database.conversationDao().getConversationById(sessionId) == null) {
            database.conversationDao().insertConversation(ConversationEntity(id = sessionId, title = str(R.string.new_conversation)))
        }
        database.chatDao().insertMessage(
            ChatMessageEntity(id = UUID.randomUUID().toString(), sessionId = sessionId, isUser = false, content = text,
                attachedDocId = doc.id, attachedDocTitle = doc.title, attachedDocType = doc.fileType)
        )
        database.conversationDao().updateLastMessage(sessionId, text.take(60))
    }
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
                    // Offline documents that were never saved (app closed before "Save to Library").
                    File(context.cacheDir, OFFLINE_DRAFTS_DIR).deleteRecursively()
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
            try {
                // Agent tasks interrupted by the app stopping become PAUSED (resumable); a step that
                // may have saved something is checked, never repeated blindly.
                agent.recoverInterrupted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Agent task recovery failed", e)
            }
            // The Agent panel shows the unfinished task of the conversation on screen.
            _currentSessionId.collect { id ->
                try {
                    val latest = agent.latestFor(id)?.takeIf { !it.status.isFinal }
                    if (agent.state.value.task?.sessionId != id || latest != null) agent.show(latest)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Agent task lookup failed", e)
                }
            }
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
        // A thesis conversation reopens thesis mode, so its messages go to the thesis, not to normal chat.
        if (id.startsWith("thesis-")) {
            enterThesisChat(id.removePrefix("thesis-"))
            return
        }
        if (thesis.state.value.chatProjectId != null) thesis.exitChatMode()
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
            database.agentTaskDao().deleteForSession(id)
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
    private fun smartOptions(kind: String?, overrideTemplateId: String?) = com.example.ai.orchestrator.SmartTemplateOptions(
        service = smartTemplates,
        autoFormat = autoFormat.value,
        preferredDocumentFormat = documentFormat.value,
        documentLanguage = documentLanguage.value.takeIf { it != "auto" },
        forcedSubcategory = kind,
        overrideTemplateId = overrideTemplateId
    )

    /** A document records the model that actually wrote it (another one may have taken over). */
    private suspend fun attributeToServingModel(doc: DocumentEntity): DocumentEntity {
        val served = agent.currentSession?.serving ?: return doc
        if (doc.providerUsed == served.name) return doc
        val provider = withContext(Dispatchers.IO) { database.providerDao().getProviderById(served.id) } ?: return doc
        val updated = doc.copy(
            providerUsed = provider.name,
            summary = "${provider.name} • ${provider.modelName} • ${File(doc.filePath).length() / 1024} KB"
        )
        withContext(Dispatchers.IO) { database.documentDao().updateDocument(updated) }
        return updated
    }

    /** Name of the model that served the last request, else the first one in the priority list. */
    private fun servingName(fallback: String): String = agent.currentSession?.serving?.name ?: fallback

    /** Error text for a failed request: names the model, and the priority list when several were tried. */
    private fun routedErrorMessage(e: AiException): String {
        val attempts = agent.currentSession?.attempts.orEmpty()
        return if (attempts.map { it.providerId }.distinct().size > 1) {
            str(R.string.ai_error_agent_failed, attempts.last().providerName, aiErrorReason(e))
        } else aiErrorMessage(e, attempts.lastOrNull()?.providerName ?: defaultProvider.value)
    }

    fun executePrompt(
        prompt: String,
        forceFormat: String? = null,
        fromVoice: Boolean = false,
        displayText: String? = null,
        forcedSubcategory: String? = null,
        overrideTemplateId: String? = null
    ): Boolean {
        var text = prompt.trim()
        if (text.isEmpty()) return false
        // What the conversation shows and stores as the user's message (e.g. "/docx ...").
        val shown = displayText?.trim()?.ifEmpty { null } ?: text
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
                        lastMessagePreview = shown.take(50),
                        updatedAt = System.currentTimeMillis()
                    )
                    database.conversationDao().insertConversation(conv)
                }

                // Auto-generate conversation title from the first meaningful request
                val genericTitles = listOf("New Conversation", "محادثة جديدة", "Nouvelle conversation", "New Chat")
                if (genericTitles.any { conv.title.equals(it, ignoreCase = true) }) {
                    val firstLine = shown.lineSequence().firstOrNull()?.trim() ?: shown
                    val autoTitle = if (firstLine.length > 32) firstLine.take(32) + "…" else firstLine
                    database.conversationDao().updateTitle(sessionId, autoTitle)
                }

                // Conversation context = messages stored BEFORE this one (restored after restart too).
                val history = buildContextHistory(sessionId)

                // Smart Templates: an answer to the last clarification question picks the kind of
                // document and continues the original request.
                var kind = forcedSubcategory
                if (kind == null && forceFormat == null) {
                    val last = database.chatDao().getMessagesForSessionOnce(sessionId).lastOrNull()
                    val note = last?.orchestratorNote
                    if (last != null && !last.isUser && note != null && note.startsWith(CLARIFY_NOTE)) {
                        val parts = note.split('|', limit = 3)
                        val choices = parts.getOrNull(1)?.split(',').orEmpty()
                        com.example.smarttemplates.DocumentIntentDetector.resolveChoice(text, choices)?.let { picked ->
                            kind = picked
                            text = parts.getOrNull(2)?.ifBlank { null } ?: text
                        }
                    }
                }

                database.conversationDao().updateLastMessage(sessionId, shown.take(60))
                database.chatDao().insertMessage(
                    ChatMessageEntity(id = UUID.randomUUID().toString(), sessionId = sessionId, isUser = true, content = shown)
                )

                // Agent Mode: a request that needs several tools (read named Library documents,
                // share the result) runs as a planned task. Everything else is Quick Mode below.
                if (forceFormat == null && kind == null) {
                    val intent = agent.analyze(text)
                    if (intent.mode == com.example.agent.core.AgentMode.AGENT) {
                        val task = com.example.agent.core.TaskPlanner.plan(
                            taskId = com.example.agent.AgentService.newId(), sessionId = sessionId, request = text,
                            intent = intent, language = appLanguage.value, now = System.currentTimeMillis(),
                            newId = com.example.agent.AgentService::newId
                        )
                        runAgentTask(task, history, listener, fromVoice)
                        return@launchChatOperation
                    }
                }

                val result = orchestrator.handleChatTurn(
                    history = history,
                    userMessage = text,
                    uiLanguage = appLanguage.value,
                    selectedTemplate = _selectedTemplateForChat.value,
                    forceFormat = DocumentFormat.parse(forceFormat),
                    listener = listener,
                    smart = smartOptions(kind, overrideTemplateId)
                )

                // The kind of document is unclear: one short question with choices (no AI request was made).
                result.clarification?.let { c ->
                    ensureActive()
                    val question = buildString {
                        append(str(R.string.st_clarify_question))
                        c.choices.forEachIndexed { i, id -> append("\n").append(i + 1).append(". ").append(templateKindName(id)) }
                    }
                    database.chatDao().insertMessage(
                        ChatMessageEntity(id = UUID.randomUUID().toString(), sessionId = sessionId, isUser = false, content = question,
                            orchestratorNote = CLARIFY_NOTE + "|" + c.choices.joinToString(",") + "|" + text)
                    )
                    database.conversationDao().updateLastMessage(sessionId, question.take(60))
                    return@launchChatOperation
                }

                // Only the provider's real answer is stored; failures are never saved as replies.
                // A cancelled operation stops here and stores nothing.
                ensureActive()
                val doc = result.document?.let { attributeToServingModel(it) }
                val servedBy = servingName(result.provider.name)
                val replyId = UUID.randomUUID().toString()
                // Smart Templates: say which kind of document and template were used, and how to
                // fill in what was left as [placeholders].
                val replyText = buildString {
                    append(result.reply)
                    result.intent?.subcategoryId?.let { sub ->
                        append("\n\n").append(str(R.string.st_reply_kind, templateKindName(sub),
                            if (result.needsTemplateChoice) str(R.string.st_neutral_formatting) else templateDisplayName(result.template?.templateId)))
                    }
                    if (result.needsTemplateChoice) append("\n").append(str(R.string.st_reply_choose_template))
                    if (doc != null && result.intent != null && Regex("""\[[^\]\n]{2,40}\]""").containsMatchIn(doc.textContent)) append("\n").append(str(R.string.st_reply_placeholders))
                }
                database.chatDao().insertMessage(
                    ChatMessageEntity(
                        id = replyId,
                        sessionId = sessionId,
                        isUser = false,
                        content = replyText,
                        attachedDocId = doc?.id,
                        attachedDocTitle = doc?.title,
                        attachedDocType = doc?.fileType,
                        orchestratorNote = when {
                            doc == null -> servedBy
                            result.templateApplied -> "Template Preserved • $servedBy"
                            else -> "Orchestrator Verified • $servedBy"
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
                _snackBarMessage.value = routedErrorMessage(e)
            } catch (e: Exception) {
                if (fromVoice) endVoiceConversation()
                reportError("executePrompt", e)
            }
        }
        if (!started && fromVoice) {
            endVoiceConversation()
            _snackBarMessage.value = str(R.string.voice_busy)
        }
        return started
    }

    // ------------------------------------------------------------------ Agent Mode tasks

    /** What the Agent panel shows: the task of this conversation and the routing status. */
    val agentState: StateFlow<com.example.agent.AgentUiState> get() = agent.state

    /** The model priority list (Settings > Agent). */
    val agentPriority: StateFlow<List<com.example.agent.routing.PriorityEntry>> =
        agent.priorityEntries.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val agentMaxCycles: StateFlow<Int> = agent.maxCycles.stateIn(viewModelScope, SharingStarted.Eagerly, com.example.agent.routing.RoutingPolicy.DEFAULT_CYCLES)

    fun moveAgentModel(from: Int, to: Int) = launchSafely("moveAgentModel") { agent.moveProvider(from, to) }
    fun setAgentModelEnabled(id: String, enabled: Boolean) = launchSafely("setAgentModelEnabled") { agent.setProviderEnabled(id, enabled) }
    fun setAgentMaxCycles(cycles: Int) = launchSafely("setAgentMaxCycles") { agent.setMaxCycles(cycles) }

    /** Runs (or continues) [start] in the current chat operation and publishes what it produced. */
    private suspend fun runAgentTask(
        start: com.example.agent.core.AgentTask,
        history: List<ContextMessage>,
        listener: AiProgressListener,
        fromVoice: Boolean
    ) {
        agentAttempts.clear()
        agent.show(start)
        agentTurn = history to listener
        val stopped = try {
            agent.engine.run(start)
        } finally {
            agentTurn = null
        }
        publishAgentResult(stopped, fromVoice)
    }

    private suspend fun publishAgentResult(stopped: com.example.agent.core.AgentTask, fromVoice: Boolean) {
        val task = stopped.copy(attempts = stopped.attempts + agentAttempts.toList())
        agentAttempts.clear()
        agent.store.save(task)
        agent.show(task)
        val generate = task.step("generate")
        val validate = task.step("validate")
        val outcome = validate?.output?.get("outcome")
        if (generate != null && generate.status == com.example.agent.core.StepStatus.COMPLETED &&
            validate != null && validate.status == com.example.agent.core.StepStatus.COMPLETED &&
            outcome != com.example.agent.core.ValidationOutcome.FAILED.name
        ) storeAgentReply(task, generate.output, outcome, fromVoice)
        if (task.status == com.example.agent.core.TaskStatus.FAILED) {
            if (fromVoice) endVoiceConversation()
            _snackBarMessage.value = agentFailureText(task)
        }
    }

    /** Stores the task's result in the conversation once (a resumed task does not store it again). */
    private suspend fun storeAgentReply(task: com.example.agent.core.AgentTask, generated: Map<String, String>, outcome: String?, fromVoice: Boolean) {
        val id = "agent-reply-${task.id}"
        if (database.chatDao().getMessagesForSessionOnce(task.sessionId).any { it.id == id }) return
        val doc = generated["documentId"]?.let { database.documentDao().getDocumentById(it) }
        val reply = buildString {
            append(generated["reply"].orEmpty())
            when (outcome) {
                com.example.agent.core.ValidationOutcome.INCOMPLETE.name -> append("\n\n").append(str(R.string.agent_result_incomplete))
                com.example.agent.core.ValidationOutcome.COMPLETED_WITH_WARNINGS.name -> append("\n\n").append(str(R.string.agent_result_warnings))
            }
        }.trim().ifEmpty { doc?.title.orEmpty() }
        val served = task.attempts.lastOrNull { it.error == null }?.providerName ?: defaultProvider.value
        database.chatDao().insertMessage(
            ChatMessageEntity(
                id = id, sessionId = task.sessionId, isUser = false, content = reply,
                attachedDocId = doc?.id, attachedDocTitle = doc?.title, attachedDocType = doc?.fileType,
                orchestratorNote = "Agent • $served"
            )
        )
        database.conversationDao().updateLastMessage(task.sessionId, reply.take(60))
        if (doc != null) _snackBarMessage.value = str(R.string.msg_generated_copied)
        if (fromVoice && _voiceConversationActive.value) {
            pendingVoiceReplyId = id
            speakMessage(id, reply)
        }
    }

    private fun agentFailureText(task: com.example.agent.core.AgentTask): String {
        val code = task.error.orEmpty()
        val kind = code.removePrefix("ai:").takeIf { code.startsWith("ai:") }
            ?.let { runCatching { AiErrorKind.valueOf(it) }.getOrNull() }
        if (kind != null) {
            val e = AiException(kind)
            val tried = task.attempts.map { it.providerId }.distinct()
            return if (tried.size > 1) str(R.string.ai_error_agent_failed, task.attempts.last().providerName, aiErrorReason(e))
            else aiErrorMessage(e, task.attempts.lastOrNull()?.providerName ?: defaultProvider.value)
        }
        return str(R.string.agent_failed, code.ifBlank { "?" })
    }

    /** Pause: the running step finishes, then the task waits (its state is saved). */
    fun pauseAgentTask() {
        agent.state.value.task?.takeIf { it.status == com.example.agent.core.TaskStatus.RUNNING }?.let { agent.requestPause(it.id) }
    }

    fun resumeAgentTask() = continueAgentTask(approval = null)

    /** Answer to the approval prompt of the shown task. */
    fun answerAgentApproval(approved: Boolean) = continueAgentTask(approval = approved)

    /** Cancel: a running task stops at once (like Stop); a paused or waiting one is closed. */
    fun cancelAgentTask() {
        val shown = agent.state.value.task ?: return
        if (shown.status.isFinal) return
        if (chatJob != null && _generatingSessionId.value == shown.sessionId) {
            cancelGeneration()
            return
        }
        launchSafely("cancelAgentTask") {
            agent.store.load(shown.id)?.takeIf { !it.status.isFinal }?.let { agent.engine.cancelled(it) }
        }
    }

    /** Hides a finished task's panel (the task record and the conversation stay). */
    fun dismissAgentTask() = agent.show(null)

    private fun continueAgentTask(approval: Boolean?) {
        val shown = agent.state.value.task ?: return
        val started = launchChatOperation(shown.sessionId) { listener ->
            try {
                var task = agent.store.load(shown.id) ?: return@launchChatOperation
                if (approval != null) {
                    val step = task.pendingApproval ?: return@launchChatOperation
                    task = agent.engine.decide(task, step.id, approval)
                }
                if (task.status.isFinal) return@launchChatOperation
                runAgentTask(task, buildContextHistory(task.sessionId), listener, fromVoice = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError("continueAgentTask", e)
            }
        }
        if (!started) _snackBarMessage.value = str(R.string.agent_busy)
    }

    // ------------------------------------------------------------------ Offline Formatting Agent

    /**
     * Local, rule-based formatting of long pasted text (no AI provider, no API key, no network).
     * It does not use [orchestrator] or [providerManager]: only the template repository, the
     * document engines and the Library.
     */
    val offlineFormatting = OfflineFormattingController(
        scope = viewModelScope,
        matcher = TemplateMatcher(RoomTemplateRepository(database.templateDao())),
        generator = OfflineDocumentGenerator(File(context.cacheDir, OFFLINE_DRAFTS_DIR)),
        library = RoomOfflineLibrary(File(context.filesDir, "generated_docs"), database.documentDao()),
        onMessage = { _snackBarMessage.value = offlineMessageText(it) },
        onSaved = { doc, _ -> addOfflineChatNote(doc) }
    )

    private fun offlineMessageText(message: OfflineMessage): String = when (message) {
        is OfflineMessage.Saved -> str(R.string.offline_saved, message.title)
        is OfflineMessage.TooLong -> str(R.string.offline_too_long, message.maxCharacters)
        OfflineMessage.NothingToFormat -> str(R.string.offline_nothing)
        OfflineMessage.AnalysisFailed -> str(R.string.offline_analysis_failed)
        OfflineMessage.GenerationFailed -> str(R.string.offline_generation_failed)
        OfflineMessage.SaveFailed -> str(R.string.offline_save_failed)
        OfflineMessage.Cancelled -> str(R.string.offline_cancelled)
    }

    /** Shows the saved document in the current conversation (no AI request is made). */
    private suspend fun addOfflineChatNote(doc: DocumentEntity) {
        val sessionId = _currentSessionId.value
        if (database.conversationDao().getConversationById(sessionId) == null) {
            database.conversationDao().insertConversation(
                ConversationEntity(id = sessionId, title = str(R.string.new_conversation))
            )
        }
        val note = str(R.string.offline_chat_note, doc.title)
        database.chatDao().insertMessage(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                isUser = false,
                content = note,
                attachedDocId = doc.id,
                attachedDocTitle = doc.title,
                attachedDocType = doc.fileType,
                orchestratorNote = "${RoomOfflineLibrary.SOURCE} • ${RuleBasedFormattingEngine.ID}"
            )
        )
        database.conversationDao().updateLastMessage(sessionId, note.take(60))
    }

    // ------------------------------------------------------------------ Thesis Builder

    private val thesisRepository = ThesisRepository(
        db = database,
        root = File(context.filesDir, "thesis"),
        imageInspector = { file ->
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, o)
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        }
    )

    /**
     * Thesis Builder: structured thesis projects edited through Chat. AI tasks go through
     * [orchestrator] with the default provider; formatting and documents are produced locally.
     */
    override val thesis = ThesisController(
        scope = viewModelScope,
        repository = thesisRepository,
        ai = OrchestratorThesisAi(
            run = { system, user, listener -> orchestrator.runThesisTask(system, user, listener).second },
            defaultProviderName = { database.providerDao().getDefaultProvider()?.name }
        ),
        exporter = ThesisExporter(
            repository = thesisRepository,
            templates = RoomTemplateRepository(database.templateDao(), database.smartTemplateDao()),
            outputDir = File(context.cacheDir, "thesis_exports"),
            pdfRenderer = { profile, snapshot, assets, file -> ThesisPdfRenderer(profile).render(snapshot, assets, file) }
        ),
        previewDir = File(context.cacheDir, "thesis_preview"),
        onMessage = { _snackBarMessage.value = thesisMessageText(it) },
        onChatNote = { addThesisChatNote(it) },
        onSaveToLibrary = { export, projectId -> saveThesisExport(export, projectId) },
        formDraftFile = File(context.filesDir, "thesis/new_thesis_draft.json")
    )

    init {
        // Declared after the thesis properties: they are initialized when this runs.
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Thesis exports never moved to the Library and revision files of interrupted saves.
                    File(context.cacheDir, "thesis_exports").deleteRecursively()
                    thesisRepository.cleanUpInterrupted()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Thesis clean-up failed", e)
            }
        }
    }

    /** Conversation that holds the Chat exchanges of one thesis project. */
    private fun thesisConversationId(projectId: String) = "thesis-$projectId"

    private var sessionBeforeThesis: String? = null

    // ThesisHost (used by the Thesis Builder workspace).
    override val templateList: StateFlow<List<TemplateEntity>> get() = allTemplates
    override val defaultContentLanguage: String get() = appLanguage.value
    override val feedback: StateFlow<String?> get() = snackBarMessage
    override fun consumeFeedback() = clearSnackBar()
    override fun continueThesisInChat(projectId: String, sectionId: String?) = enterThesisChat(projectId, sectionId)
    override fun openExportedDocument(document: DocumentEntity) = openDocument(document)
    override fun shareExportedDocument(document: DocumentEntity) = shareDocument(document)
    override suspend fun aiProviderInfo(): Pair<String, String>? = withContext(Dispatchers.IO) {
        database.providerDao().getDefaultProvider()?.let { it.name to it.modelName }
    }
    private val extractionEngine by lazy { com.example.extraction.DocumentExtractionEngine(context, ocrEngine) }
    override val ocrLanguageCode: String get() = ocrLanguage.value
    override fun isPdf(uri: Uri): Boolean = extractionEngine.isPdf(uri)
    override suspend fun extractDocument(
        sources: List<com.example.extraction.ExtractionSource>,
        languageCode: String,
        progress: (page: Int, total: Int) -> Unit
    ): com.example.extraction.ExtractedDocument =
        extractionEngine.extract(sources, OcrLanguage.fromCode(languageCode), appLanguage.value) { page, total -> progress(page, total) }

    override suspend fun recentChatMessages(limit: Int): List<com.example.thesis.ui.ChatSnippet> = withContext(Dispatchers.IO) {
        database.chatDao().getRecentChatMessagesNow(limit)
            .filter { it.attachedDocType != THESIS_PROPOSAL_CARD && it.content.isNotBlank() }
            .map { com.example.thesis.ui.ChatSnippet(it.content, it.isUser, it.timestamp) }
    }

    /**
     * Chat sends the following messages and images to [projectId] (until [exitThesisChat]);
     * [sectionId] is the target section for content that names no destination.
     */
    fun enterThesisChat(projectId: String, sectionId: String? = null) {
        launchSafely("enterThesisChat") {
            val project = thesisRepository.project(projectId) ?: return@launchSafely
            val conversationId = thesisConversationId(projectId)
            if (database.conversationDao().getConversationById(conversationId) == null) {
                database.conversationDao().insertConversation(ConversationEntity(id = conversationId, title = project.title))
            }
            if (!_currentSessionId.value.startsWith("thesis-")) sessionBeforeThesis = _currentSessionId.value
            if (_voiceConversationActive.value) stopVoiceConversation()
            _currentSessionId.value = conversationId
            thesis.enterChatMode(projectId, sectionId)
            setNavTab(AppNavTab.CHAT)
        }
    }

    fun exitThesisChat() {
        thesis.exitChatMode()
        val back = sessionBeforeThesis
        sessionBeforeThesis = null
        launchSafely("exitThesisChat") {
            val target = back?.takeIf { database.conversationDao().getConversationById(it) != null }
                ?: allConversations.value.firstOrNull { !it.id.startsWith("thesis-") }?.id
                ?: UUID.randomUUID().toString().also { database.conversationDao().insertConversation(ConversationEntity(id = it, title = str(R.string.new_conversation))) }
            _currentSessionId.value = target
            preferences.setActiveConversationId(target)
        }
    }

    /** Opens a thesis project from the Library. */
    fun openThesisFromLibrary(projectId: String) {
        setNavTab(AppNavTab.THESIS)
        thesis.openProject(projectId)
    }

    private fun sendToThesis(text: String): SendResult {
        val projectId = thesis.state.value.chatProjectId ?: return SendResult.BUSY
        if (thesis.state.value.busy != null) return SendResult.BUSY
        val conversationId = thesisConversationId(projectId)
        launchSafely("sendToThesis") {
            database.chatDao().insertMessage(ChatMessageEntity(id = UUID.randomUUID().toString(), sessionId = conversationId, isUser = true, content = text))
            database.conversationDao().updateLastMessage(conversationId, text.take(60))
        }
        thesis.submitFromChat(text)
        return SendResult.SENT
    }

    /** Images attached in Chat while a thesis is active become thesis figures (placement is reviewed). */
    fun addThesisImages(uris: List<Uri>, instruction: String) {
        val resolver = context.contentResolver
        val images = uris.map { uri ->
            val name = try {
                resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } catch (e: Exception) { null } ?: "image"
            Triple<() -> java.io.InputStream?, String, String>({ resolver.openInputStream(uri) }, name, resolver.getType(uri) ?: "image/jpeg")
        }
        val projectId = thesis.state.value.chatProjectId ?: return
        launchSafely("addThesisImages") {
            val conversationId = thesisConversationId(projectId)
            val shown = instruction.ifBlank { str(R.string.thesis_images_attached, uris.size) }
            database.chatDao().insertMessage(ChatMessageEntity(id = UUID.randomUUID().toString(), sessionId = conversationId, isUser = true, content = shown))
        }
        thesis.addImagesFromChat(images, instruction)
    }

    private suspend fun addThesisChatNote(note: ThesisChatNote) {
        val conversationId = thesisConversationId(note.projectId)
        if (database.conversationDao().getConversationById(conversationId) == null) {
            val title = thesisRepository.project(note.projectId)?.title ?: str(R.string.thesis_builder)
            database.conversationDao().insertConversation(ConversationEntity(id = conversationId, title = title))
        }
        val text = thesisNoteText(note.text)
        database.chatDao().insertMessage(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(), sessionId = conversationId, isUser = false, content = text,
                attachedDocId = note.proposalId, attachedDocType = if (note.proposalId != null) THESIS_PROPOSAL_CARD else null,
                orchestratorNote = str(R.string.thesis_builder)
            )
        )
        database.conversationDao().updateLastMessage(conversationId, text.take(60))
    }

    private val thesisNotes: Map<String, Int> = mapOf(
        "thesis_note_applied" to R.string.thesis_note_applied,
        "thesis_note_change" to R.string.thesis_note_change,
        "thesis_note_choose_destination" to R.string.thesis_note_choose_destination,
        "thesis_note_edit" to R.string.thesis_note_edit,
        "thesis_note_heading_text" to R.string.thesis_note_heading_text,
        "thesis_note_image" to R.string.thesis_note_image,
        "thesis_note_no_changes" to R.string.thesis_note_no_changes,
        "thesis_note_no_previous" to R.string.thesis_note_no_previous,
        "thesis_note_paragraph_missing" to R.string.thesis_note_paragraph_missing,
        "thesis_note_proposal" to R.string.thesis_note_proposal,
        "thesis_note_rejected" to R.string.thesis_note_rejected,
        "thesis_note_restore_review" to R.string.thesis_note_restore_review,
        "thesis_note_restored" to R.string.thesis_note_restored,
        "thesis_note_section_empty" to R.string.thesis_note_section_empty,
        "thesis_note_sent_to_provider" to R.string.thesis_note_sent_to_provider,
        "thesis_note_suggested" to R.string.thesis_note_suggested,
        "thesis_note_table" to R.string.thesis_note_table,
        "thesis_note_table_data" to R.string.thesis_note_table_data,
        "thesis_note_unknown" to R.string.thesis_note_unknown,
        "thesis_note_where" to R.string.thesis_note_where,
        "thesis_note_which_section" to R.string.thesis_note_which_section
    )

    private fun thesisNoteText(t: ThesisNoteText): String {
        val res = thesisNotes[t.code] ?: return t.code
        return try { str(res, *t.args.toTypedArray()) } catch (e: Exception) { str(res) }
    }

    private fun thesisMessageText(m: ThesisMessage): String = when (m) {
        is ThesisMessage.Created -> str(R.string.thesis_msg_created, m.title)
        is ThesisMessage.Applied -> str(R.string.thesis_msg_applied, m.revision)
        ThesisMessage.Rejected -> str(R.string.thesis_msg_rejected)
        is ThesisMessage.Conflict -> str(R.string.thesis_msg_conflict)
        is ThesisMessage.Restored -> str(R.string.thesis_msg_restored, m.revision)
        is ThesisMessage.Exported -> str(R.string.thesis_msg_exported, m.title)
        is ThesisMessage.AiFailed -> aiErrorMessage(m.error, defaultProvider.value)
        ThesisMessage.AiInvalid -> str(R.string.thesis_msg_ai_invalid)
        ThesisMessage.Cancelled -> str(R.string.chat_generation_stopped)
        is ThesisMessage.ImageFailed -> str(R.string.thesis_msg_image_failed)
        is ThesisMessage.Failed -> str(R.string.thesis_msg_failed)
        ThesisMessage.Deleted -> str(R.string.thesis_msg_deleted)
        ThesisMessage.NothingToFormat -> str(R.string.thesis_msg_nothing_to_format)
        ThesisMessage.Saved -> str(R.string.thesis_msg_saved)
        ThesisMessage.DraftSaved -> str(R.string.thesis_msg_draft_saved)
        ThesisMessage.Renamed -> str(R.string.thesis_msg_renamed)
        ThesisMessage.ExtractionWrongSection -> str(R.string.dx_wrong_section)
        ThesisMessage.ExtractionNothing -> str(R.string.dx_nothing_to_insert)
        ThesisMessage.ExtractionFailed -> str(R.string.dx_failed)
    }

    /** Copies an export into the Library folder and registers it (linked to the project). */
    private suspend fun saveThesisExport(export: ThesisExport, projectId: String): DocumentEntity = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "generated_docs").apply { mkdirs() }
        val ext = export.file.extension
        val target = DocumentFileNames.createUniqueFile(dir, export.file.nameWithoutExtension, ext)
        if (!export.file.renameTo(target)) { export.file.copyTo(target, overwrite = false); export.file.delete() }
        val snapshot = thesisRepository.snapshot(projectId)
        val text = snapshot.orderedElements().joinToString("\n\n") { it.text }.take(200_000)
        val entity = DocumentEntity(
            id = UUID.randomUUID().toString(),
            title = target.name,
            fileType = if (ext == "pdf") "PDF" else "DOCX",
            filePath = target.absolutePath,
            fileSize = target.length(),
            wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() },
            pageOrSlideCount = export.pages.coerceAtLeast(snapshot.outline(false).size),
            templateId = export.template.entity?.id,
            templateName = export.template.entity?.name,
            providerUsed = THESIS_SOURCE,
            projectId = projectId,
            summary = "${snapshot.meta.title} • ${target.length() / 1024} KB",
            textContent = text,
            isOrchestratorVerified = false
        )
        database.documentDao().insertDocument(entity)
        entity
    }

    // ------------------------------------------------------------------ built-in slash commands

    private val conversationCleaner = ConversationCleaner(database)

    private val _clearDialogVisible = MutableStateFlow(false)
    /** "/clear" was sent: the "Clear Conversations?" dialog is shown (nothing is deleted yet). */
    val clearDialogVisible: StateFlow<Boolean> = _clearDialogVisible.asStateFlow()

    /**
     * Sends the composer text. A built-in command ("/docx ...", "/summarize ...", "/clear") is
     * routed to the existing orchestrator path for it; any other text is a normal chat message.
     * Every AI command goes through [CentralAiOrchestrator] with the default provider.
     */
    fun sendMessage(message: String): SendResult {
        val text = message.trim()
        if (text.isEmpty()) return SendResult.NEEDS_INPUT
        // Thesis mode: the message goes to the active thesis project (never to normal chat).
        if (thesis.state.value.chatProjectId != null) return sendToThesis(text)
        val parsed = SlashCommands.parse(text)
            ?: return if (executePrompt(text)) SendResult.SENT else SendResult.BUSY
        return when (val action = parsed.command.action) {
            SlashAction.Clear -> {
                _clearDialogVisible.value = true
                SendResult.CLEAR_DIALOG
            }
            is SlashAction.Document -> {
                if (parsed.argument.isBlank()) {
                    _snackBarMessage.value = str(R.string.command_needs_document_brief, parsed.command.token)
                    SendResult.NEEDS_INPUT
                } else if (executePrompt(parsed.argument, forceFormat = action.format.name, displayText = text)) {
                    SendResult.SENT
                } else SendResult.BUSY
            }
            is SlashAction.Content -> runContentCommand(action.operation, parsed.command, parsed.argument, text)
        }
    }

    /**
     * Content command: the text after the command is the content. Without text, the most recent
     * document in this conversation is used; without either, the user is asked for content.
     */
    private fun runContentCommand(
        operation: ContentOperation,
        command: SlashCommand,
        argument: String,
        shown: String
    ): SendResult {
        val documentId = if (argument.isBlank()) chatMessages.value.lastOrNull { it.attachedDocId != null }?.attachedDocId else null
        if (argument.isBlank() && documentId == null) {
            _snackBarMessage.value = str(R.string.command_needs_content, command.token)
            return SendResult.NEEDS_INPUT
        }
        val sessionId = _currentSessionId.value
        val started = launchChatOperation(sessionId) { listener ->
            try {
                val document = documentId?.let { database.documentDao().getDocumentById(it) }
                if (documentId != null && document == null) {
                    _snackBarMessage.value = str(R.string.msg_file_not_found)
                    return@launchChatOperation
                }
                if (database.conversationDao().getConversationById(sessionId) == null) {
                    database.conversationDao().insertConversation(
                        ConversationEntity(id = sessionId, title = str(R.string.new_conversation))
                    )
                }
                database.chatDao().insertMessage(
                    ChatMessageEntity(id = UUID.randomUUID().toString(), sessionId = sessionId, isUser = true, content = shown)
                )
                database.conversationDao().updateLastMessage(sessionId, shown.take(60))

                val (result, provider) = orchestrator.performContentOperation(
                    operation = operation,
                    content = document?.textContent ?: argument,
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
                        attachedDocId = document?.id,
                        attachedDocTitle = document?.title,
                        attachedDocType = document?.fileType,
                        orchestratorNote = "${command.token} • ${provider.name}"
                    )
                )
                database.conversationDao().updateLastMessage(sessionId, result.take(60))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                _snackBarMessage.value = aiErrorMessage(e, defaultProvider.value)
            } catch (e: Exception) {
                reportError("runContentCommand", e)
            }
        }
        return if (started) SendResult.SENT else SendResult.BUSY
    }

    fun dismissClearDialog() {
        _clearDialogVisible.value = false
    }

    /** Stops an operation that would write into conversations that are being deleted. */
    private fun stopOperationFor(sessionIds: Set<String>?) {
        val running = _generatingSessionId.value ?: return
        if (chatJob == null || (sessionIds != null && running !in sessionIds)) return
        chatOperationId++
        chatJob?.cancel(CancellationException("Conversation cleared"))
        finishChatOperation()
        if (_voiceConversationActive.value) stopVoiceConversation()
    }

    /** "Clear Current Chat" (confirmed in the dialog): empties the active conversation only. */
    fun clearCurrentConversation() {
        _clearDialogVisible.value = false
        val sessionId = _currentSessionId.value
        stopOperationFor(setOf(sessionId))
        viewModelScope.launch {
            try {
                conversationCleaner.clearConversation(sessionId, str(R.string.new_conversation))
                _snackBarMessage.value = str(R.string.clear_current_done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "clearCurrentConversation failed", e)
                _snackBarMessage.value = str(R.string.clear_failed)
            }
        }
    }

    /**
     * "Delete All Conversations" (confirmed twice): deletes every conversation and message, then
     * opens a new empty conversation. Documents, templates, providers and keys are kept.
     */
    fun deleteAllConversations() {
        _clearDialogVisible.value = false
        stopOperationFor(null)
        viewModelScope.launch {
            try {
                conversationCleaner.deleteAllConversations()
                val newId = UUID.randomUUID().toString()
                database.conversationDao().insertConversation(
                    ConversationEntity(id = newId, title = str(R.string.new_conversation))
                )
                _currentSessionId.value = newId
                preferences.setActiveConversationId(newId)
                _snackBarMessage.value = str(R.string.clear_all_done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "deleteAllConversations failed", e)
                _snackBarMessage.value = str(R.string.clear_failed)
            }
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
                if (chatOperationId == operationId && streamRestartedOp != operationId) _streamingReply.value = text
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
        agent.clearRouting()
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
        // A running Agent task is recorded as cancelled once its coroutine has stopped.
        agent.state.value.task?.takeIf { !it.status.isFinal }?.let { shown ->
            viewModelScope.launch {
                job.join()
                agent.store.load(shown.id)?.takeIf { !it.status.isFinal }?.let { agent.engine.cancelled(it) }
            }
        }
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
                // Scanned and offline-formatted documents stay on the device: their text is only
                // sent to the provider when the user explicitly runs an AI action on them.
                database.documentDao().getDocumentById(docId)
                    ?.takeIf { it.providerUsed != LOCAL_OCR_SOURCE && it.providerUsed != RoomOfflineLibrary.SOURCE && it.providerUsed != THESIS_SOURCE }
                    ?.textContent
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
        // A Smart Templates document is drawn again from its content with its template.
        if (doc.contentJson != null && doc.fileType == "DOCX") {
            launchSafely("smartPdf") { smartPdf(doc) }
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

    private val templatesDir: File get() = File(context.filesDir, "templates")

    private val templateImporter by lazy {
        TemplateImporter(
            templatesDir = templatesDir,
            dao = database.templateDao(),
            analysis = templateAnalysisEngine,
            pdfThumbnail = { pdf, id -> TemplateThumbnails.renderPdfFirstPage(pdf, TemplateThumbnails.fileFor(templatesDir, id)) },
            // Scanned PDF templates: structure from the first pages, read on the device.
            scannedLayout = { pdf ->
                extractionEngine.extract(
                    listOf(com.example.extraction.ExtractionSource(Uri.fromFile(pdf), isPdf = true, pages = setOf(0, 1, 2))),
                    OcrLanguage.fromCode(ocrLanguage.value), appLanguage.value
                )
            }
        )
    }

    /**
     * Imports a DOCX, PPTX, XLSX or PDF template picked with the Storage Access Framework. Copy,
     * type check and analysis run locally off the main thread; [sourceFileName] is the picked
     * file's name (shown for the template).
     */
    fun importTemplateFromUri(uri: Uri, userDefinedName: String, sourceFileName: String? = null) {
        viewModelScope.launch {
            try {
                val entity = templateImporter.import(
                    open = { context.contentResolver.openInputStream(uri) },
                    userName = userDefinedName,
                    sourceFileName = sourceFileName
                )
                _snackBarMessage.value = when {
                    entity.compatibilityStatus == "Review" -> str(R.string.template_pdf_imported_no_text, entity.name)
                    else -> str(R.string.msg_template_imported, entity.name)
                }
                // Smart Templates: suggest a category from the file name; the user confirms it.
                val (category, sub) = smartTemplates.suggestClassification(sourceFileName ?: entity.name, entity.fileType)
                _pendingTemplateClassification.value = PendingClassification(entity, category, sub)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TemplateImportException) {
                _snackBarMessage.value = str(
                    when (e.reason) {
                        TemplateImportException.Reason.UNREADABLE -> R.string.template_import_unreadable
                        TemplateImportException.Reason.TOO_LARGE -> R.string.template_import_too_large
                        TemplateImportException.Reason.UNSUPPORTED -> R.string.template_import_unsupported
                        TemplateImportException.Reason.PDF_ENCRYPTED -> R.string.template_import_pdf_encrypted
                        TemplateImportException.Reason.PDF_DAMAGED -> R.string.template_import_pdf_damaged
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "importTemplateFromUri failed", e)
                _snackBarMessage.value = "Template import failed: ${e.message}"
            }
        }
    }

    /** First-page image of a PDF template (rendered on the device and cached), or null. */
    suspend fun templateThumbnail(template: TemplateEntity): android.graphics.Bitmap? {
        if (!template.fileType.equals("PDF", ignoreCase = true)) return null
        return withContext(Dispatchers.IO) {
            TemplateThumbnails.load(templatesDir, template.id, File(template.originalFilePath))
        }
    }

    /**
     * Opens the original PDF of a template in a PDF viewer. A read-only copy in the app's export
     * cache is shared (the template folder itself is never exposed); the original is unchanged.
     */
    fun openTemplateOriginal(template: TemplateEntity) {
        launchSafely("openTemplateOriginal") {
            val source = File(template.originalFilePath)
            if (!source.isFile) {
                _snackBarMessage.value = str(R.string.msg_file_not_found)
                return@launchSafely
            }
            val copy = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "pdf_exports").apply { mkdirs() }
                val name = TemplateProfile.fromJson(template.profileJson).sourceFileName.ifBlank { template.name }
                File(dir, DocumentFileNames.sanitizeBaseName(DocumentFileNames.stripDocumentExtension(name.removeSuffix(".pdf"))) + ".pdf").also {
                    source.copyTo(it, overwrite = true)
                }
            }
            deliveryManager.openFile(copy, DeliveryManager.MIME_PDF)
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
            // Its Smart Templates defaults and favorite go with it (the built-in best match applies again).
            smartTemplates.forget(template.id)
            if (_pendingTemplateClassification.value?.template?.id == template.id) _pendingTemplateClassification.value = null
            if (template.originalFilePath.isNotBlank()) {
                withContext(Dispatchers.IO) { File(template.originalFilePath).delete() }
            }
            withContext(Dispatchers.IO) { TemplateThumbnails.fileFor(templatesDir, template.id).delete() }
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
                        "PDF" -> {
                            dao.clearDefaultPdf()
                            dao.setDefaultPdfFlag(template.id, true)
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
                    "PDF" -> dao.setDefaultPdfFlag(template.id, false)
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
                    "PDF" -> "pdf"
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
        // Leaving the app (for example to copy text for Paste Text) can end the process: unsaved
        // section text is saved now so it is not lost.
        thesis.saveIfDirty()
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

    private val _modelCatalog = MutableStateFlow(ModelCatalogUiState())
    /** Model list loaded for the provider dialog (Settings). */
    val modelCatalog: StateFlow<ModelCatalogUiState> = _modelCatalog.asStateFlow()
    private var modelCatalogJob: Job? = null

    /**
     * Loads the provider's real model list with [apiKey] (or the stored key of [providerId] when
     * the field is empty). Only the provider being configured is contacted.
     */
    fun loadProviderModels(type: AiProviderType, baseUrl: String, apiKey: String, providerId: String?) {
        modelCatalogJob?.cancel()
        _modelCatalog.value = ModelCatalogUiState(loading = true, type = type)
        modelCatalogJob = viewModelScope.launch {
            _modelCatalog.value = try {
                val models = providerManager.fetchModels(type, baseUrl, apiKey, providerId)
                if (models == null) ModelCatalogUiState(type = type, unsupported = true)
                else ModelCatalogUiState(type = type, models = models)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                ModelCatalogUiState(type = type, error = aiErrorReason(e))
            } catch (e: Exception) {
                Log.e(TAG, "loadProviderModels failed", e)
                ModelCatalogUiState(type = type, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun clearProviderModels() {
        modelCatalogJob?.cancel()
        _modelCatalog.value = ModelCatalogUiState()
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
     * Recognizes the scanned / picked page images ON THE DEVICE (PaddleOCR on ONNX Runtime). Nothing is sent to
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

    /**
     * Extracts a PDF of the Library with the shared extraction engine (pages that carry text are
     * read directly, scanned pages by on-device OCR) and opens the review in Chat, where the text
     * can be corrected, copied or inserted into the message. Nothing is sent automatically.
     */
    fun extractLibraryDocument(doc: com.example.data.model.DocumentEntity) {
        if (_isScanning.value) return
        val file = java.io.File(doc.filePath)
        if (!doc.fileType.equals("PDF", ignoreCase = true) || !file.isFile) {
            _snackBarMessage.value = str(R.string.library_extract_unavailable)
            return
        }
        scanJob = viewModelScope.launch {
            _isScanning.value = true
            _scanProgress.value = str(R.string.recognizing_text)
            setNavTab(AppNavTab.CHAT)
            try {
                val extracted = extractionEngine.extract(
                    listOf(com.example.extraction.ExtractionSource(Uri.fromFile(file), isPdf = true)),
                    OcrLanguage.fromCode(ocrLanguage.value), appLanguage.value
                ) { page, total -> _scanProgress.value = str(R.string.scan_progress_page, page, total) }
                val pages = extracted.pages.map { p -> p.toScannedPage(p.imageUri.orEmpty()) }
                _scannedReviewState.value = ScannedDocumentReviewState(
                    documentTitle = doc.title,
                    pages = pages,
                    activePageIndex = 0,
                    selectedTemplateId = null,
                    isAiEnhanced = false
                )
                reportScanOutcome(pages, extracted.pages.count { it.error != null && it.error != "no_text_found" })
            } catch (e: CancellationException) {
                throw e
            } catch (e: OcrUnavailableException) {
                Log.e(TAG, "OCR unavailable", e)
                _snackBarMessage.value = str(R.string.scan_ocr_unavailable)
            } catch (e: Exception) {
                reportError("extractLibraryDocument", e)
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
                // Pages read directly from a PDF's text have no image: they are kept as they are.
                val withImage = current.pages.indices.filter { current.pages[it].imageUriString.isNotBlank() }
                if (withImage.isEmpty()) return@launch
                val result = ocrEngine.processPages(
                    pageUris = withImage.map { Uri.parse(current.pages[it].imageUriString) },
                    language = OcrLanguage.fromCode(code),
                    appLanguage = appLanguage.value,
                    onProgress = { page, total -> _scanProgress.value = str(R.string.scan_progress_page, page, total) }
                )
                _scannedReviewState.value?.let { state ->
                    val pages = state.pages.toMutableList()
                    withImage.forEachIndexed { k, i -> result.pages.getOrNull(k)?.let { if (i in pages.indices) pages[i] = it.copy(pageIndex = i) } }
                    _scannedReviewState.value = state.copy(pages = pages, isAiEnhanced = false, editedPages = state.editedPages - withImage.toSet())
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

    /**
     * Reads one page again with the language mode [code]; the other pages and their corrections
     * are kept. (The review asks first when this page was corrected.)
     */
    fun retryScannedPage(pageIndex: Int, code: String) {
        val current = _scannedReviewState.value ?: return
        val page = current.pages.getOrNull(pageIndex) ?: return
        if (_isScanning.value) return
        if (page.imageUriString.isBlank()) {
            // Read directly from a PDF's text: there is no image to recognize again.
            _snackBarMessage.value = str(R.string.scan_page_from_pdf_text)
            return
        }
        scanJob = viewModelScope.launch {
            _isScanning.value = true
            _scanProgress.value = str(R.string.scan_progress_page, 1, 1)
            try {
                val result = ocrEngine.processPages(listOf(Uri.parse(page.imageUriString)), OcrLanguage.fromCode(code), appLanguage.value, firstPageIndex = pageIndex)
                val again = result.pages.firstOrNull() ?: return@launch
                _scannedReviewState.value?.let { state ->
                    if (pageIndex in state.pages.indices) {
                        _scannedReviewState.value = state.copy(
                            pages = state.pages.toMutableList().also { it[pageIndex] = again },
                            editedPages = state.editedPages - pageIndex
                        )
                    }
                }
                reportScanOutcome(result.pages, result.failedPages)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OcrUnavailableException) {
                _snackBarMessage.value = str(R.string.scan_ocr_unavailable)
            } catch (e: Exception) {
                reportError("retryScannedPage", e)
            } finally {
                _isScanning.value = false
                _scanProgress.value = null
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
            ).copy(extraction = oldPage.extraction) // the page report stays (how the page was read)
            currentPages[pageIndex] = updatedPage
            _scannedReviewState.value = current.copy(pages = currentPages, editedPages = current.editedPages + pageIndex)
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
                    str(R.string.scan_docx_ready, docEntity.title, reviewState.pages.size)
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
