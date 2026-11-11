package com.example.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.R
import com.example.ai.orchestrator.CentralAiOrchestrator
import com.example.ai.orchestrator.TaskLifecycleState
import com.example.ai.providers.AiConnectionTester
import com.example.core.delivery.DeliveryManager
import com.example.core.security.KeystoreHelper
import com.example.core.storage.DocumentFileNames
import com.example.core.storage.PreferencesManager
import com.example.core.storage.PreferencesSeedFlagStore
import com.example.core.storage.SampleDataSeeder
import com.example.core.voice.VoiceAssistantManager
import com.example.core.voice.VoiceState
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ConversationEntity
import com.example.data.model.DocumentEntity
import com.example.data.model.ProviderEntity
import com.example.data.model.TemplateEntity
import com.example.template.analysis.TemplateAnalysisEngine
import com.example.template.domain.TemplateProfile
import com.example.template.preview.TemplatePreviewEngine
import com.example.template.preview.TemplatePreviewResult
import com.example.ocr.DocumentOcrEngine
import com.example.ocr.ScannedDocumentStructureReconstructor
import com.example.document.model.ScannedPageData
import com.example.document.model.ScannedDocumentReviewState
import com.example.document.docx.DocxEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
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

    private companion object {
        const val TAG = "SmartBubbleViewModel"
    }

    private val context = application.applicationContext
    val database = SmartBubbleDatabase.getDatabase(context)
    val preferences = PreferencesManager(context)
    val deliveryManager = DeliveryManager(context)
    val orchestrator = CentralAiOrchestrator(context, database, deliveryManager)
    val templateAnalysisEngine = TemplateAnalysisEngine()
    val templatePreviewEngine = TemplatePreviewEngine()
    val ocrEngine = DocumentOcrEngine(context, orchestrator)
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

    // Orchestration status
    val taskState = orchestrator.currentState
    val taskStatusMessage = orchestrator.statusMessage

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _snackBarMessage = MutableStateFlow<String?>(null)
    val snackBarMessage: StateFlow<String?> = _snackBarMessage.asStateFlow()

    // Audio playback state
    private val _isPlayingAudio = MutableStateFlow(false)
    val isPlayingAudio: StateFlow<Boolean> = _isPlayingAudio.asStateFlow()

    // Voice assistant
    val voiceAssistant = VoiceAssistantManager(context) { recognizedText ->
        executePrompt(recognizedText)
    }
    val voiceState = voiceAssistant.voiceState
    val voiceDecibel = voiceAssistant.decibelLevel

    // Settings preferences
    val defaultProvider = preferences.defaultProvider.stateIn(viewModelScope, SharingStarted.Eagerly, "OpenAI")
    val appLanguage = preferences.appLanguage.stateIn(viewModelScope, SharingStarted.Eagerly, "en")
    val appTheme = preferences.appTheme.stateIn(viewModelScope, SharingStarted.Eagerly, "dark")

    init {
        viewModelScope.launch {
            try {
                // One-time sample seeding + consistency maintenance, off the main thread.
                withContext(Dispatchers.IO) { sampleDataSeeder.runStartupTasks() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Start-up data maintenance failed", e)
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
        viewModelScope.launch {
            val newId = UUID.randomUUID().toString()
            val newConv = ConversationEntity(
                id = newId,
                title = context.getString(R.string.new_conversation),
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
        viewModelScope.launch {
            _currentSessionId.value = id
            preferences.setActiveConversationId(id)
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            database.conversationDao().updateTitle(id, newTitle.trim())
            _snackBarMessage.value = context.getString(R.string.msg_conversation_renamed)
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            database.conversationDao().deleteConversationById(id)
            database.chatDao().deleteMessagesBySessionId(id)
            _snackBarMessage.value = context.getString(R.string.msg_conversation_deleted)

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
            _snackBarMessage.value = context.getString(R.string.msg_template_selected, template.name)
        }
    }

    fun executePrompt(prompt: String, forceFormat: String? = null) {
        if (prompt.isBlank()) return
        val sessionId = _currentSessionId.value

        viewModelScope.launch {
            _isGenerating.value = true

            // Ensure conversation exists in Room
            var conv = database.conversationDao().getConversationById(sessionId)
            if (conv == null) {
                conv = ConversationEntity(
                    id = sessionId,
                    title = context.getString(R.string.new_conversation),
                    lastMessagePreview = prompt.take(50),
                    updatedAt = System.currentTimeMillis()
                )
                database.conversationDao().insertConversation(conv)
            }

            // Auto-generate conversation title from the first meaningful request
            val genericTitles = listOf("New Conversation", "محادثة جديدة", "Nouvelle conversation", "New Chat")
            if (genericTitles.any { conv.title.equals(it, ignoreCase = true) }) {
                val firstLine = prompt.lineSequence().firstOrNull()?.trim() ?: prompt.trim()
                val autoTitle = if (firstLine.length > 32) firstLine.take(32) + "…" else firstLine
                database.conversationDao().updateTitle(sessionId, autoTitle)
            }

            // Update conversation preview and timestamp
            database.conversationDao().updateLastMessage(sessionId, prompt.take(60))

            // Insert user message
            val userMsg = ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                isUser = true,
                content = prompt
            )
            database.chatDao().insertMessage(userMsg)

            val templateProfile = _selectedTemplateForChat.value?.let {
                TemplateProfile.fromJson(it.profileJson)
            }
            val originalTemplateFile = _selectedTemplateForChat.value?.originalFilePath?.let { File(it) }?.takeIf { it.exists() }

            val result = orchestrator.executeUserRequest(
                userPrompt = prompt,
                defaultProviderName = defaultProvider.value,
                selectedTemplate = templateProfile,
                templateFile = originalTemplateFile,
                forceFormat = forceFormat
            )

            if (result.state == TaskLifecycleState.COMPLETED) {
                val assistantMsg = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    isUser = false,
                    content = result.conversationalReply,
                    attachedDocId = result.documentEntity?.id,
                    attachedDocTitle = result.documentEntity?.title,
                    attachedDocType = result.documentEntity?.fileType,
                    orchestratorNote = if (originalTemplateFile != null) "Template Preserved" else "Orchestrator Verified"
                )
                database.chatDao().insertMessage(assistantMsg)
                database.conversationDao().updateLastMessage(sessionId, result.conversationalReply.take(60))
                _snackBarMessage.value = context.getString(R.string.msg_generated_copied)
            } else {
                val errorMsg = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    isUser = false,
                    content = "Generation failed: ${result.errorMessage ?: "Unknown error"}"
                )
                database.chatDao().insertMessage(errorMsg)
            }
            _isGenerating.value = false
        }
    }

    fun openDocument(doc: DocumentEntity) {
        val file = File(doc.filePath)
        val mime = when (doc.fileType) {
            "DOCX" -> DeliveryManager.MIME_DOCX
            "PPTX" -> DeliveryManager.MIME_PPTX
            "XLSX" -> DeliveryManager.MIME_XLSX
            else -> DeliveryManager.MIME_PDF
        }
        deliveryManager.openFile(file, mime)
    }

    fun shareDocument(doc: DocumentEntity) {
        val file = File(doc.filePath)
        val mime = when (doc.fileType) {
            "DOCX" -> DeliveryManager.MIME_DOCX
            "PPTX" -> DeliveryManager.MIME_PPTX
            "XLSX" -> DeliveryManager.MIME_XLSX
            else -> DeliveryManager.MIME_PDF
        }
        deliveryManager.shareFile(file, mime, doc.title)
    }

    fun exportDocumentAsPdf(doc: DocumentEntity) {
        viewModelScope.launch {
            if (doc.fileType == "PDF") {
                openDocument(doc)
                return@launch
            }
            val pdfDir = File(context.filesDir, "generated_docs").apply { mkdirs() }
            val pdfFile = File(pdfDir, "${doc.title.substringBeforeLast('.')}_Export.pdf")
            val wordModel = com.example.document.model.WordDocumentModel(
                title = doc.title.substringBeforeLast('.'),
                subtitle = "Exported from SmartBubble Library",
                sections = listOf(
                    com.example.document.model.WordSection(
                        heading = "Document Content",
                        paragraphs = listOf(doc.textContent)
                    )
                )
            )
            com.example.document.pdf.PdfEngine().generatePdf(wordModel, pdfFile, TemplateProfile.defaultProfile())
            deliveryManager.openFile(pdfFile, DeliveryManager.MIME_PDF)
            _snackBarMessage.value = "PDF Export ready & opened."
        }
    }

    fun renameDocument(doc: DocumentEntity, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            try {
                // Only the visible title changes; the physical file name stays independent.
                val title = DocumentFileNames.displayTitle(newTitle)
                database.documentDao().updateTitle(doc.id, title)
                _snackBarMessage.value = context.getString(R.string.msg_document_renamed, title)
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
                }
                _snackBarMessage.value = context.getString(R.string.msg_document_deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError("deleteDocument", e)
            }
        }
    }

    fun importTemplateFromUri(uri: Uri, userDefinedName: String) {
        viewModelScope.launch {
            try {
                val extension = getMimeExtension(uri)
                val templateDir = File(context.filesDir, "templates").apply { mkdirs() }
                val persistentTemplateFile = File(templateDir, "template_${UUID.randomUUID()}.$extension")

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(persistentTemplateFile).use { output ->
                        input.copyTo(output)
                    }
                }
                val profile = templateAnalysisEngine.analyzeFile(persistentTemplateFile, userDefinedName)
                val templateEntity = TemplateEntity(
                    id = profile.templateId,
                    name = profile.name,
                    fileType = profile.originalFileType,
                    originalFilePath = persistentTemplateFile.absolutePath,
                    fileSize = persistentTemplateFile.length(),
                    profileJson = profile.toJson(),
                    compatibilityStatus = "Compatible",
                    thumbnailColorHex = profile.primaryColorHex
                )
                database.templateDao().insertTemplate(templateEntity)
                _snackBarMessage.value = context.getString(R.string.msg_template_imported, profile.name)
            } catch (e: Exception) {
                _snackBarMessage.value = "Template import failed: ${e.message}"
            }
        }
    }

    private val isUpdatingDefault = java.util.concurrent.atomic.AtomicBoolean(false)

    fun deleteTemplate(template: TemplateEntity) {
        viewModelScope.launch {
            if (template.originalFilePath.isNotBlank()) {
                File(template.originalFilePath).delete()
            }
            if (_selectedTemplateForChat.value?.id == template.id) {
                _selectedTemplateForChat.value = null
            }
            database.templateDao().deleteTemplate(template)
            _snackBarMessage.value = context.getString(R.string.msg_template_deleted)
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
                _snackBarMessage.value = context.getString(R.string.msg_template_set_default, template.name, template.fileType)
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
                _snackBarMessage.value = context.getString(R.string.msg_template_removed_default, template.name)
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
            val result = templatePreviewEngine.loadPreview(template)
            _previewResult.value = result
            _isPreviewLoading.value = false
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
                    context.getString(R.string.template_duplicate_name_format, template.name)
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
                _snackBarMessage.value = context.getString(R.string.msg_template_duplicated, resolvedName)
            } catch (e: Exception) {
                _snackBarMessage.value = "Failed to duplicate template: ${e.message}"
            }
        }
    }

    private fun getMimeExtension(uri: Uri): String {
        val type = context.contentResolver.getType(uri) ?: ""
        return when {
            type.contains("word") -> "docx"
            type.contains("presentation") -> "pptx"
            type.contains("sheet") -> "xlsx"
            else -> "docx"
        }
    }

    // Voice & Audio
    fun speakText(text: String) {
        val locale = when (appLanguage.value) {
            "ar" -> Locale("ar")
            "fr" -> Locale.FRENCH
            else -> Locale.US
        }
        _isPlayingAudio.value = true
        voiceAssistant.speak(text, locale)
    }

    fun stopSpeaking() {
        voiceAssistant.stopSpeaking()
        _isPlayingAudio.value = false
    }

    // AI Providers Management
    fun setDefaultProvider(provider: ProviderEntity) {
        viewModelScope.launch {
            database.providerDao().clearDefault()
            database.providerDao().setAsDefault(provider.id)
            preferences.setDefaultProvider(provider.name)
            _snackBarMessage.value = context.getString(R.string.msg_provider_set_default, provider.name)
        }
    }

    fun testProviderConnection(provider: ProviderEntity) {
        viewModelScope.launch {
            _snackBarMessage.value = context.getString(R.string.msg_testing_connection, provider.name)
            val tester = AiConnectionTester()
            val result = tester.testConnection(provider)
            val updated = provider.copy(status = result.message)
            database.providerDao().updateProvider(updated)
            _snackBarMessage.value = "${provider.name}: ${result.message}"
        }
    }

    fun addProvider(name: String, type: String, baseUrl: String, apiKey: String, model: String) {
        viewModelScope.launch {
            val newProvider = ProviderEntity(
                id = UUID.randomUUID().toString(),
                name = name,
                providerType = type,
                baseUrl = baseUrl,
                encryptedApiKey = KeystoreHelper.encrypt(apiKey),
                modelName = model,
                isDefault = false,
                status = "Ready"
            )
            database.providerDao().insertProvider(newProvider)
            _snackBarMessage.value = context.getString(R.string.msg_provider_added, name)
        }
    }

    fun updateProvider(provider: ProviderEntity) {
        viewModelScope.launch {
            database.providerDao().updateProvider(provider)
        }
    }

    fun deleteProvider(provider: ProviderEntity) {
        viewModelScope.launch {
            database.providerDao().deleteProvider(provider)
            _snackBarMessage.value = context.getString(R.string.msg_provider_deleted)
        }
    }

    // Settings
    fun setAppLanguage(lang: String) {
        viewModelScope.launch {
            preferences.setAppLanguage(lang)
        }
    }

    fun setAppTheme(theme: String) {
        viewModelScope.launch {
            preferences.setAppTheme(theme)
        }
    }

    // Document Scanning & OCR Workflows
    fun processScannedPages(uris: List<Uri>, useAi: Boolean = true) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isScanning.value = true
            _scanProgress.value = context.getString(R.string.recognizing_text)
            try {
                val pages = ocrEngine.processPages(
                    pageUris = uris,
                    useAiEnhancement = useAi,
                    defaultProviderName = defaultProvider.value
                ) { pageIndex, total, msg ->
                    _scanProgress.value = msg
                }

                if (pages.isEmpty()) {
                    _snackBarMessage.value = context.getString(R.string.scan_empty_error)
                    _isScanning.value = false
                    return@launch
                }

                val autoTitle = pages.firstOrNull()?.recognizedTitle?.takeIf { it.isNotBlank() && !it.startsWith("Page") && !it.startsWith("Scanned") }
                    ?: ("Document_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(java.util.Date()))

                _scannedReviewState.value = ScannedDocumentReviewState(
                    documentTitle = autoTitle,
                    pages = pages,
                    activePageIndex = 0,
                    selectedTemplateId = null,
                    isAiEnhanced = useAi
                )
            } catch (e: Exception) {
                _snackBarMessage.value = "OCR processing failed: ${e.message}"
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
                rawText = updatedText,
                lines = emptyList()
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
            _snackBarMessage.value = context.getString(R.string.scan_empty_error)
            return
        }
        val currentPages = current.pages.toMutableList()
        if (pageIndex in currentPages.indices) {
            currentPages.removeAt(pageIndex)
            val nextIndex = (pageIndex - 1).coerceAtLeast(0)
            _scannedReviewState.value = current.copy(pages = currentPages, activePageIndex = nextIndex)
        }
    }

    fun setSelectedScanTemplate(templateId: String?) {
        val current = _scannedReviewState.value ?: return
        _scannedReviewState.value = current.copy(selectedTemplateId = templateId)
    }

    fun dismissScanReview() {
        _scannedReviewState.value = null
    }

    fun generateDocxFromScannedDocument() {
        val reviewState = _scannedReviewState.value ?: return
        val sessionId = _currentSessionId.value

        viewModelScope.launch {
            _isGenerating.value = true
            _scanProgress.value = context.getString(R.string.generate_word_doc)
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

                // Insert into Room DocumentDao
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
                    providerUsed = defaultProvider.value,
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
                val scanActionLabel = context.getString(R.string.chat_scan_document)
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

                _snackBarMessage.value = context.getString(R.string.msg_generated_copied)
                _scannedReviewState.value = null
            } catch (e: CancellationException) {
                if (!documentSaved) withContext(NonCancellable + Dispatchers.IO) { createdFile?.delete() }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "generateDocxFromScannedDocument failed", e)
                if (!documentSaved) withContext(Dispatchers.IO) { createdFile?.delete() }
                _snackBarMessage.value = "Failed to generate Word document: ${e.message}"
            } finally {
                _isGenerating.value = false
                _scanProgress.value = null
            }
        }
    }

    fun executeFollowUpAiAction(actionType: String, docId: String) {
        val sessionId = _currentSessionId.value
        viewModelScope.launch {
            _isGenerating.value = true
            try {
                val doc = database.documentDao().getDocumentById(docId)
                if (doc == null) {
                    _snackBarMessage.value = "Document not found"
                    _isGenerating.value = false
                    return@launch
                }

                val userActionPrompt = when (actionType.uppercase()) {
                    "SPELLING" -> context.getString(R.string.scan_ai_spelling)
                    "FORMAT" -> context.getString(R.string.scan_ai_format)
                    "TRANSLATE" -> context.getString(R.string.scan_ai_translate)
                    "SUMMARIZE" -> context.getString(R.string.scan_ai_summarize)
                    else -> actionType
                }

                val userMsg = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    isUser = true,
                    content = "$userActionPrompt: ${doc.title}"
                )
                database.chatDao().insertMessage(userMsg)

                val result = orchestrator.performFollowUpAiAction(
                    actionType = actionType,
                    documentText = doc.textContent,
                    providerName = defaultProvider.value
                )

                val assistantMsg = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    isUser = false,
                    content = result,
                    attachedDocId = doc.id,
                    attachedDocTitle = doc.title,
                    attachedDocType = doc.fileType,
                    orchestratorNote = "AI Refinement: $userActionPrompt"
                )
                database.chatDao().insertMessage(assistantMsg)
                database.conversationDao().updateLastMessage(sessionId, result.take(60))
            } catch (e: Exception) {
                _snackBarMessage.value = "AI action failed: ${e.message}"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    /** Logs an unexpected failure and shows the existing snackbar feedback instead of crashing. */
    private fun reportError(operation: String, e: Throwable) {
        Log.e(TAG, "$operation failed", e)
        _snackBarMessage.value = context.getString(
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
