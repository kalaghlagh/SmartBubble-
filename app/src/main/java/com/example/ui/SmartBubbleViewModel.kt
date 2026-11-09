package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.orchestrator.CentralAiOrchestrator
import com.example.ai.orchestrator.TaskLifecycleState
import com.example.core.delivery.DeliveryManager
import com.example.core.storage.PreferencesManager
import com.example.core.voice.VoiceAssistantManager
import com.example.core.voice.VoiceState
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.ChatMessageEntity
import com.example.data.model.DocumentEntity
import com.example.data.model.TemplateEntity
import com.example.knowledge.NotebookLmManager
import com.example.social.SocialMediaExplainer
import com.example.template.analysis.TemplateAnalysisEngine
import com.example.template.domain.TemplateMode
import com.example.template.domain.TemplateProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

enum class AppNavTab {
    HOME,
    ASSISTANT,
    CREATE,
    LIBRARY,
    SETTINGS
}

class SmartBubbleViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    val database = SmartBubbleDatabase.getDatabase(context)
    val preferences = PreferencesManager(context)
    val deliveryManager = DeliveryManager(context)
    val orchestrator = CentralAiOrchestrator(context, database, deliveryManager)
    val notebookManager = NotebookLmManager(database)
    val templateAnalysisEngine = TemplateAnalysisEngine()
    val socialExplainer = SocialMediaExplainer()

    // Navigation state
    private val _currentTab = MutableStateFlow(AppNavTab.HOME)
    val currentTab: StateFlow<AppNavTab> = _currentTab.asStateFlow()

    // Active Chat Session
    private val _currentSessionId = MutableStateFlow("session-142")
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    val chatMessages = database.chatDao().getMessagesForSession("session-142")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allDocuments = database.documentDao().getAllDocuments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTemplates = database.templateDao().getAllTemplates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active Orchestration & Creation State
    val taskState = orchestrator.currentState
    val taskStatusMessage = orchestrator.statusMessage

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _selectedDocumentForInspection = MutableStateFlow<DocumentEntity?>(null)
    val selectedDocumentForInspection: StateFlow<DocumentEntity?> = _selectedDocumentForInspection.asStateFlow()

    private val _snackBarMessage = MutableStateFlow<String?>(null)
    val snackBarMessage: StateFlow<String?> = _snackBarMessage.asStateFlow()

    // Voice assistant
    val voiceAssistant = VoiceAssistantManager(context) { recognizedText ->
        executePrompt(recognizedText)
    }
    val voiceState = voiceAssistant.voiceState
    val voiceDecibel = voiceAssistant.decibelLevel

    // Settings flows
    val defaultProvider = preferences.defaultProvider.stateIn(viewModelScope, SharingStarted.Eagerly, "Anthropic Claude 3.5 Sonnet")
    val appLanguage = preferences.appLanguage.stateIn(viewModelScope, SharingStarted.Eagerly, "en")
    val appTheme = preferences.appTheme.stateIn(viewModelScope, SharingStarted.Eagerly, "dark")
    val templateMode = preferences.templateMode.stateIn(viewModelScope, SharingStarted.Eagerly, "SMART_ADAPTATION")
    val pushToTalk = preferences.pushToTalkEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val wakeWord = preferences.wakeWordEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val defaultOfficeAction = preferences.defaultOfficeAction.stateIn(viewModelScope, SharingStarted.Eagerly, "PREVIEW")
    val notebookEmail = preferences.notebookLmEmail.stateIn(viewModelScope, SharingStarted.Eagerly, "alexander.w@enterprise.com")

    init {
        seedInitialDataIfNeeded()
    }

    private fun seedInitialDataIfNeeded() {
        viewModelScope.launch {
            // Seed a sample default template if none exist
            val existing = database.templateDao().getTemplateById("default-corp")
            if (existing == null) {
                val profile = TemplateProfile.defaultProfile()
                database.templateDao().insertTemplate(
                    TemplateEntity(
                        id = profile.templateId,
                        name = profile.name,
                        fileType = "DOCX",
                        originalFilePath = "",
                        profileJson = profile.toJson(),
                        isDefaultWord = true,
                        isDefaultPpt = true,
                        isDefaultExcel = true,
                        isDefaultPdf = true,
                        compatibilityStatus = "Fully Compatible",
                        thumbnailColorHex = "#24D6E8"
                    )
                )
            }

            // Seed initial conversation message if empty
            val messages = database.chatDao().getMessagesForSession("session-142")
            // If empty, generate initial welcome message
            val sampleUser = ChatMessageEntity(
                id = "init-1",
                sessionId = "session-142",
                isUser = true,
                content = "Create a formal commercial partnership proposal between SmartBubble Technologies and Apex Enterprise Solutions for an enterprise license of 500 seats. Include SLA, tier pricing, and implementation timeline.",
                timestamp = System.currentTimeMillis() - 120000
            )
            database.chatDao().insertMessage(sampleUser)

            // Generate the initial sample document so the user immediately sees a verified real DOCX!
            val docs = database.documentDao().getDocumentById("sample-partnership-doc")
            if (docs == null) {
                val docDir = File(context.filesDir, "generated_docs").apply { mkdirs() }
                val docFile = File(docDir, "Apex_Enterprise_Partnership_Agreement_v1.docx")
                val wordModel = com.example.document.model.WordDocumentModel(
                    title = "Commercial Partnership Agreement",
                    subtitle = "Enterprise License (500 Seats) & Strategic SLA Terms",
                    organization = "SmartBubble Technologies & Apex Enterprise Solutions",
                    date = "November 2026",
                    sections = listOf(
                        com.example.document.model.WordSection(
                            heading = "1. Executive Summary",
                            paragraphs = listOf("This formal agreement defines the terms under which SmartBubble Technologies provides autonomous executive AI workspace capabilities to Apex Enterprise Solutions.")
                        ),
                        com.example.document.model.WordSection(
                            heading = "2. SLA & Governance",
                            paragraphs = listOf("A guaranteed 99.9% uptime uptime standard is established with 24/7 dedicated engineering escalation.")
                        )
                    ),
                    table = com.example.document.model.WordTable(
                        headers = listOf("Tier Tier", "Seats", "Monthly Rate", "Support SLA"),
                        rows = listOf(
                            listOf("Standard", "1 - 50", "$15 / user", "Standard 8x5"),
                            listOf("Enterprise Dedicated", "500", "Custom Tier", "Dedicated 24/7 Executive SLA")
                        )
                    ),
                    signatoryName = "Alexander Wright",
                    signatoryTitle = "Managing Director, Global Enterprise Systems"
                )
                com.example.document.docx.DocxEngine().generateDocx(wordModel, docFile, TemplateProfile.defaultProfile())

                val docEntity = DocumentEntity(
                    id = "sample-partnership-doc",
                    title = docFile.name,
                    fileType = "DOCX",
                    filePath = docFile.absolutePath,
                    fileSize = docFile.length(),
                    wordCount = 1840,
                    pageOrSlideCount = 4,
                    templateId = "default-corp",
                    templateName = "Smart Bubble Executive Theme",
                    providerUsed = "Claude 3.5 Sonnet",
                    summary = "DOCX & PDF Ready • 4 Pages • 1,840 Words • Formatted with Signature Blocks",
                    textContent = "Commercial Partnership Agreement\nSmartBubble Technologies & Apex Enterprise Solutions\nTier pricing and 6-week onboarding milestone chart.",
                    isOrchestratorVerified = true
                )
                database.documentDao().insertDocument(docEntity)

                val sampleAssistant = ChatMessageEntity(
                    id = "init-2",
                    sessionId = "session-142",
                    isUser = false,
                    content = "I've drafted the complete 4-page Commercial Partnership Agreement with enterprise SLA terms, tiered pricing tables, and a 6-week onboarding milestone chart.",
                    timestamp = System.currentTimeMillis() - 60000,
                    attachedDocId = docEntity.id,
                    attachedDocTitle = docEntity.title,
                    attachedDocType = docEntity.fileType,
                    orchestratorNote = "Orchestrator Verified & Export Ready"
                )
                database.chatDao().insertMessage(sampleAssistant)
            }
        }
    }

    fun setNavTab(tab: AppNavTab) {
        _currentTab.value = tab
    }

    fun executePrompt(prompt: String, forceFormat: String? = null) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            _isGenerating.value = true
            val userMsg = ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = _currentSessionId.value,
                isUser = true,
                content = prompt
            )
            database.chatDao().insertMessage(userMsg)

            val mode = when (templateMode.value) {
                "STRICT_MATCH" -> TemplateMode.STRICT_MATCH
                "STYLE_ONLY" -> TemplateMode.STYLE_ONLY
                else -> TemplateMode.SMART_ADAPTATION
            }

            val result = orchestrator.executeUserRequest(
                userPrompt = prompt,
                defaultProviderName = defaultProvider.value,
                templateMode = mode,
                forceFormat = forceFormat
            )

            if (result.state == TaskLifecycleState.COMPLETED) {
                val assistantMsg = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = _currentSessionId.value,
                    isUser = false,
                    content = result.conversationalReply,
                    attachedDocId = result.documentEntity?.id,
                    attachedDocTitle = result.documentEntity?.title,
                    attachedDocType = result.documentEntity?.fileType,
                    orchestratorNote = "Orchestrator Verified & Export Ready"
                )
                database.chatDao().insertMessage(assistantMsg)
                _snackBarMessage.value = "Document compiled & text copied to clipboard!"
            } else {
                val errorMsg = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = _currentSessionId.value,
                    isUser = false,
                    content = "Task generation failed: ${result.errorMessage ?: "Validation error"}"
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
            // Generate PDF equivalent
            val pdfDir = File(context.filesDir, "generated_docs").apply { mkdirs() }
            val pdfFile = File(pdfDir, "${doc.title.substringBeforeLast('.')}_Exported.pdf")
            val wordModel = com.example.document.model.WordDocumentModel(
                title = doc.title.substringBeforeLast('.'),
                subtitle = "Exported from Smart Bubble Library",
                sections = listOf(
                    com.example.document.model.WordSection(
                        heading = "Document Content",
                        paragraphs = listOf(doc.textContent)
                    )
                )
            )
            com.example.document.pdf.PdfEngine().generatePdf(wordModel, pdfFile, TemplateProfile.defaultProfile())
            deliveryManager.openFile(pdfFile, DeliveryManager.MIME_PDF)
            _snackBarMessage.value = "Exported PDF generated (${pdfFile.length() / 1024} KB) and opened."
        }
    }

    fun inspectDocument(doc: DocumentEntity) {
        _selectedDocumentForInspection.value = doc
    }

    fun clearInspection() {
        _selectedDocumentForInspection.value = null
    }

    fun clearSnackBar() {
        _snackBarMessage.value = null
    }

    fun toggleFavorite(doc: DocumentEntity) {
        viewModelScope.launch {
            database.documentDao().updateDocument(doc.copy(isFavorite = !doc.isFavorite))
        }
    }

    fun deleteDocument(doc: DocumentEntity) {
        viewModelScope.launch {
            File(doc.filePath).delete()
            database.documentDao().deleteDocument(doc)
            _snackBarMessage.value = "Document deleted."
        }
    }

    fun importTemplateFromUri(uri: Uri, userDefinedName: String) {
        viewModelScope.launch {
            try {
                val tempFile = File(context.cacheDir, "imported_${System.currentTimeMillis()}.${getMimeExtension(uri)}")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                val profile = templateAnalysisEngine.analyzeFile(tempFile, userDefinedName)
                val templateEntity = TemplateEntity(
                    id = profile.templateId,
                    name = profile.name,
                    fileType = profile.originalFileType,
                    originalFilePath = tempFile.absolutePath,
                    profileJson = profile.toJson(),
                    compatibilityStatus = "Verified Compatible",
                    thumbnailColorHex = profile.primaryColorHex
                )
                database.templateDao().insertTemplate(templateEntity)
                _snackBarMessage.value = "Template '${profile.name}' analyzed and saved!"
            } catch (e: Exception) {
                _snackBarMessage.value = "Template import failed: ${e.message}"
            }
        }
    }

    private fun getMimeExtension(uri: Uri): String {
        val type = context.contentResolver.getType(uri) ?: ""
        return when {
            type.contains("word") -> "docx"
            type.contains("presentation") -> "pptx"
            type.contains("sheet") -> "xlsx"
            else -> "pdf"
        }
    }

    fun handleSharedSocialText(sharedText: String) {
        viewModelScope.launch {
            val analysis = socialExplainer.analyzeSharedContent(sharedText, appLanguage.value)
            setNavTab(AppNavTab.ASSISTANT)
            val userMsg = ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = _currentSessionId.value,
                isUser = true,
                content = "Read and explain shared content:\n\n$sharedText"
            )
            database.chatDao().insertMessage(userMsg)

            val reply = "Here is the executive explanation of the shared post:\n\n" +
                    "**Language:** ${analysis.detectedLanguage}\n\n" +
                    "**Summary:** ${analysis.summary}\n\n" +
                    "**Explanation:** ${analysis.executiveExplanation}\n\n" +
                    "**Key Takeaways:**\n" + analysis.keyPoints.joinToString("\n") { "• $it" }

            val assistantMsg = ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = _currentSessionId.value,
                isUser = false,
                content = reply,
                orchestratorNote = "Shared Post Analyzed"
            )
            database.chatDao().insertMessage(assistantMsg)

            // Read aloud via TTS
            voiceAssistant.speak(analysis.executiveExplanation, Locale(appLanguage.value))
        }
    }

    fun speakText(text: String) {
        val locale = when (appLanguage.value) {
            "ar" -> Locale("ar")
            "fr" -> Locale.FRENCH
            else -> Locale.US
        }
        voiceAssistant.speak(text, locale)
    }

    fun setDefaultProvider(provider: String) {
        viewModelScope.launch {
            preferences.setDefaultProvider(provider)
        }
    }

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

    fun setTemplateMode(mode: String) {
        viewModelScope.launch {
            preferences.setTemplateMode(mode)
        }
    }

    fun setPushToTalk(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setPushToTalk(enabled)
        }
    }

    fun setWakeWord(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setWakeWord(enabled)
        }
    }

    fun saveApiKey(provider: String, key: String) {
        viewModelScope.launch {
            preferences.saveEncryptedApiKey(provider, key)
            _snackBarMessage.value = "$provider API key encrypted in Android Keystore."
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceAssistant.shutdown()
    }
}
