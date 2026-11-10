package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.orchestrator.CentralAiOrchestrator
import com.example.ai.orchestrator.TaskLifecycleState
import com.example.ai.providers.AiConnectionTester
import com.example.core.delivery.DeliveryManager
import com.example.core.security.KeystoreHelper
import com.example.core.storage.PreferencesManager
import com.example.core.voice.VoiceAssistantManager
import com.example.core.voice.VoiceState
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.ChatMessageEntity
import com.example.data.model.DocumentEntity
import com.example.data.model.ProviderEntity
import com.example.data.model.TemplateEntity
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
    CHAT,
    TEMPLATES,
    LIBRARY,
    SETTINGS
}

class SmartBubbleViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    val database = SmartBubbleDatabase.getDatabase(context)
    val preferences = PreferencesManager(context)
    val deliveryManager = DeliveryManager(context)
    val orchestrator = CentralAiOrchestrator(context, database, deliveryManager)
    val templateAnalysisEngine = TemplateAnalysisEngine()

    // Navigation State
    private val _currentTab = MutableStateFlow(AppNavTab.CHAT)
    val currentTab: StateFlow<AppNavTab> = _currentTab.asStateFlow()

    // Active Chat Session
    private val _currentSessionId = MutableStateFlow("session-smartbubble")
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    val chatMessages = database.chatDao().getMessagesForSession("session-smartbubble")
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
    val appLanguage = preferences.appLanguage.stateIn(viewModelScope, SharingStarted.Eagerly, "ar")
    val appTheme = preferences.appTheme.stateIn(viewModelScope, SharingStarted.Eagerly, "dark")

    init {
        seedInitialDataIfNeeded()
    }

    private fun seedInitialDataIfNeeded() {
        viewModelScope.launch {
            // Seed initial AI Providers if empty
            val existingProvider = database.providerDao().getProviderById("p-openai")
            if (existingProvider == null) {
                database.providerDao().insertProvider(
                    ProviderEntity(
                        id = "p-openai",
                        name = "OpenAI",
                        providerType = "OpenAI",
                        baseUrl = "https://api.openai.com/v1",
                        encryptedApiKey = KeystoreHelper.encrypt("sk-live-smartbubble-encrypted"),
                        modelName = "GPT-4o",
                        isDefault = true,
                        status = "Connected (28ms)"
                    )
                )
                database.providerDao().insertProvider(
                    ProviderEntity(
                        id = "p-gemini",
                        name = "Google Gemini",
                        providerType = "Google Gemini",
                        baseUrl = "https://generativelanguage.googleapis.com",
                        encryptedApiKey = KeystoreHelper.encrypt("gemini-smartbubble-encrypted"),
                        modelName = "Gemini 1.5 Pro",
                        isDefault = false,
                        status = "Ready"
                    )
                )
                database.providerDao().insertProvider(
                    ProviderEntity(
                        id = "p-claude",
                        name = "Anthropic Claude",
                        providerType = "Anthropic Claude",
                        baseUrl = "https://api.anthropic.com/v1",
                        encryptedApiKey = KeystoreHelper.encrypt("sk-ant-smartbubble-encrypted"),
                        modelName = "Claude 3.5 Sonnet",
                        isDefault = false,
                        status = "Ready"
                    )
                )
            }

            // Seed initial templates matching mockup
            val t1 = database.templateDao().getTemplateById("tmpl-company-report")
            if (t1 == null) {
                val profileDoc = TemplateProfile.defaultProfile().copy(
                    templateId = "tmpl-company-report",
                    name = "Company Report Template",
                    originalFileType = "DOCX",
                    primaryColorHex = "#1D52D8",
                    headingFont = "Segoe UI Semibold"
                )
                database.templateDao().insertTemplate(
                    TemplateEntity(
                        id = profileDoc.templateId,
                        name = profileDoc.name,
                        fileType = "DOCX",
                        originalFilePath = "",
                        fileSize = 250880L, // 245 KB
                        profileJson = profileDoc.toJson(),
                        isDefaultWord = true,
                        compatibilityStatus = "Compatible",
                        thumbnailColorHex = "#1D52D8"
                    )
                )

                val profilePpt = TemplateProfile.defaultProfile().copy(
                    templateId = "tmpl-presentation-theme",
                    name = "Presentation Theme",
                    originalFileType = "PPTX",
                    primaryColorHex = "#E05D26"
                )
                database.templateDao().insertTemplate(
                    TemplateEntity(
                        id = profilePpt.templateId,
                        name = profilePpt.name,
                        fileType = "PPTX",
                        originalFilePath = "",
                        fileSize = 2202009L, // 2.1 MB
                        profileJson = profilePpt.toJson(),
                        isDefaultPpt = true,
                        compatibilityStatus = "Compatible",
                        thumbnailColorHex = "#E05D26"
                    )
                )

                val profileXlsx = TemplateProfile.defaultProfile().copy(
                    templateId = "tmpl-business-data",
                    name = "Business Data Template",
                    originalFileType = "XLSX",
                    primaryColorHex = "#107C41"
                )
                database.templateDao().insertTemplate(
                    TemplateEntity(
                        id = profileXlsx.templateId,
                        name = profileXlsx.name,
                        fileType = "XLSX",
                        originalFilePath = "",
                        fileSize = 184320L, // 180 KB
                        profileJson = profileXlsx.toJson(),
                        isDefaultExcel = true,
                        compatibilityStatus = "Compatible",
                        thumbnailColorHex = "#107C41"
                    )
                )
            }

            // Seed initial documents in Library matching mockup
            val doc1 = database.documentDao().getDocumentById("doc-rec-letter")
            if (doc1 == null) {
                val docDir = File(context.filesDir, "generated_docs").apply { mkdirs() }

                // 1. Recommendation Letter.docx
                val f1 = File(docDir, "Recommendation Letter.docx")
                if (!f1.exists()) {
                    val m1 = com.example.document.model.WordDocumentModel(
                        title = "Professional Letter of Recommendation",
                        subtitle = "Candidate: Senior Principal Architect",
                        sections = listOf(
                            com.example.document.model.WordSection(
                                heading = "Recommendation Assessment",
                                paragraphs = listOf("It is an absolute pleasure to provide this highest recommendation for enterprise leadership, architecture, and system engineering excellence.")
                            )
                        )
                    )
                    com.example.document.docx.DocxEngine().generateDocx(m1, f1, TemplateProfile.defaultProfile())
                }
                database.documentDao().insertDocument(
                    DocumentEntity(
                        id = "doc-rec-letter",
                        title = "Recommendation Letter.docx",
                        fileType = "DOCX",
                        filePath = f1.absolutePath,
                        fileSize = if (f1.exists()) f1.length() else 250880L,
                        wordCount = 680,
                        pageOrSlideCount = 2,
                        templateName = "Company Report Template",
                        providerUsed = "OpenAI GPT-4o",
                        textContent = "Professional Letter of Recommendation\nCandidate: Senior Principal Architect\nPrepared by SmartBubble"
                    )
                )

                // 2. Annual Report.pptx
                val f2 = File(docDir, "Annual Report.pptx")
                if (!f2.exists()) {
                    val m2 = com.example.document.model.PresentationModel(
                        title = "Annual Strategic Review",
                        subtitle = "Fiscal Performance & Innovation Strategy",
                        slides = listOf(
                            com.example.document.model.SlideModel(
                                title = "Executive Highlights",
                                bulletPoints = listOf("45% year-over-year revenue growth", "Global enterprise footprint expansion"),
                                calloutMetric = "+45%",
                                calloutLabel = "YoY Revenue"
                            )
                        )
                    )
                    com.example.document.pptx.PptxEngine().generatePptx(m2, f2, TemplateProfile.defaultProfile())
                }
                database.documentDao().insertDocument(
                    DocumentEntity(
                        id = "doc-annual-report",
                        title = "Annual Report.pptx",
                        fileType = "PPTX",
                        filePath = f2.absolutePath,
                        fileSize = if (f2.exists()) f2.length() else 1887436L,
                        wordCount = 890,
                        pageOrSlideCount = 8,
                        templateName = "Presentation Theme",
                        providerUsed = "OpenAI GPT-4o",
                        textContent = "Annual Strategic Review - Fiscal Performance & Innovation Strategy"
                    )
                )

                // 3. Sales Data.xlsx
                val f3 = File(docDir, "Sales Data.xlsx")
                if (!f3.exists()) {
                    val m3 = com.example.document.model.SpreadsheetModel(
                        workbookTitle = "Quarterly Sales Data",
                        sheets = listOf(
                            com.example.document.model.SheetModel(
                                name = "Regional Breakdown",
                                headers = listOf("Region", "Q1 Actual", "Q2 Actual", "Forecast"),
                                rows = listOf(
                                    listOf("North America", "$1,450,000", "$1,620,000", "$1,800,000"),
                                    listOf("EMEA", "$980,000", "$1,120,000", "$1,300,000")
                                )
                            )
                        )
                    )
                    com.example.document.xlsx.XlsxEngine().generateXlsx(m3, f3, TemplateProfile.defaultProfile())
                }
                database.documentDao().insertDocument(
                    DocumentEntity(
                        id = "doc-sales-data",
                        title = "Sales Data.xlsx",
                        fileType = "XLSX",
                        filePath = f3.absolutePath,
                        fileSize = if (f3.exists()) f3.length() else 122880L,
                        wordCount = 340,
                        pageOrSlideCount = 2,
                        templateName = "Business Data Template",
                        providerUsed = "OpenAI GPT-4o",
                        textContent = "Quarterly Sales Data\nRegional Breakdown and Financial Projections"
                    )
                )

                // 4. Project Summary.pdf
                val f4 = File(docDir, "Project Summary.pdf")
                if (!f4.exists()) {
                    val m4 = com.example.document.model.WordDocumentModel(
                        title = "Executive Project Summary",
                        subtitle = "Autonomous Document Creation & Smart Verification",
                        sections = listOf(
                            com.example.document.model.WordSection(
                                heading = "Overview",
                                paragraphs = listOf("SmartBubble verified end-to-end multi-format file synthesis, ensuring exact formatting and enterprise compliance.")
                            )
                        )
                    )
                    com.example.document.pdf.PdfEngine().generatePdf(m4, f4, TemplateProfile.defaultProfile())
                }
                database.documentDao().insertDocument(
                    DocumentEntity(
                        id = "doc-project-summary",
                        title = "Project Summary.pdf",
                        fileType = "PDF",
                        filePath = f4.absolutePath,
                        fileSize = if (f4.exists()) f4.length() else 430080L,
                        wordCount = 520,
                        pageOrSlideCount = 3,
                        providerUsed = "OpenAI GPT-4o",
                        textContent = "Executive Project Summary\nAutonomous Document Creation & Smart Verification"
                    )
                )
            }

            // Seed initial conversation message matching Arabic mockup
            val existingChat = database.chatDao().getMessagesForSession("session-smartbubble")
            val msg1 = ChatMessageEntity(
                id = "chat-seed-1",
                sessionId = "session-smartbubble",
                isUser = true,
                content = "اكتب لي تقريراً مختصراً عن الذكاء الاصطناعي",
                timestamp = System.currentTimeMillis() - 180000
            )
            database.chatDao().insertMessage(msg1)

            val msg2 = ChatMessageEntity(
                id = "chat-seed-2",
                sessionId = "session-smartbubble",
                isUser = false,
                content = "بالطبع! إليك تقرير مختصر عن الذكاء الاصطناعي:\n\nالذكاء الاصطناعي هو مجال من علوم الحاسوب يهدف إلى تطوير أنظمة قادرة على محاكاة الذكاء البشري مثل التعلم والاستنتاج واتخاذ القرار، مع إمكانية تحويل الأفكار إلى مستندات مكتبية احترافية مثل Word و PowerPoint و Excel.",
                timestamp = System.currentTimeMillis() - 120000,
                attachedDocId = "doc-project-summary",
                attachedDocTitle = "Project Summary.pdf",
                attachedDocType = "PDF",
                orchestratorNote = "Orchestrator Verified"
            )
            database.chatDao().insertMessage(msg2)
        }
    }

    fun setNavTab(tab: AppNavTab) {
        _currentTab.value = tab
    }

    fun selectTemplateForChat(template: TemplateEntity?) {
        _selectedTemplateForChat.value = template
        if (template != null) {
            _snackBarMessage.value = "Selected template: ${template.name}"
        }
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

            val templateProfile = _selectedTemplateForChat.value?.let {
                TemplateProfile.fromJson(it.profileJson)
            }

            val result = orchestrator.executeUserRequest(
                userPrompt = prompt,
                defaultProviderName = defaultProvider.value,
                selectedTemplate = templateProfile,
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
                _snackBarMessage.value = "Generated & copied to clipboard!"
            } else {
                val errorMsg = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = _currentSessionId.value,
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
            _snackBarMessage.value = "Exported PDF generated & opened."
        }
    }

    fun renameDocument(doc: DocumentEntity, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            val updated = doc.copy(title = newTitle)
            database.documentDao().updateDocument(updated)
            _snackBarMessage.value = "Renamed to $newTitle"
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
                val extension = getMimeExtension(uri)
                val tempFile = File(context.cacheDir, "imported_${System.currentTimeMillis()}.$extension")
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
                    fileSize = tempFile.length(),
                    profileJson = profile.toJson(),
                    compatibilityStatus = "Compatible",
                    thumbnailColorHex = profile.primaryColorHex
                )
                database.templateDao().insertTemplate(templateEntity)
                _snackBarMessage.value = "Template '${profile.name}' analyzed and saved!"
            } catch (e: Exception) {
                _snackBarMessage.value = "Template import failed: ${e.message}"
            }
        }
    }

    fun deleteTemplate(template: TemplateEntity) {
        viewModelScope.launch {
            if (template.originalFilePath.isNotBlank()) {
                File(template.originalFilePath).delete()
            }
            database.templateDao().deleteTemplate(template)
            _snackBarMessage.value = "Template deleted."
        }
    }

    fun setDefaultTemplate(template: TemplateEntity) {
        viewModelScope.launch {
            val updated = when (template.fileType) {
                "DOCX" -> template.copy(isDefaultWord = true)
                "PPTX" -> template.copy(isDefaultPpt = true)
                "XLSX" -> template.copy(isDefaultExcel = true)
                else -> template.copy(isDefaultWord = true)
            }
            database.templateDao().updateTemplate(updated)
            _snackBarMessage.value = "${template.name} set as default ${template.fileType} template."
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
            _snackBarMessage.value = "${provider.name} set as Central AI Orchestrator."
        }
    }

    fun testProviderConnection(provider: ProviderEntity) {
        viewModelScope.launch {
            _snackBarMessage.value = "Testing connection to ${provider.name}..."
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
            _snackBarMessage.value = "Provider $name added."
        }
    }

    fun updateProvider(provider: ProviderEntity) {
        viewModelScope.launch {
            database.providerDao().updateProvider(provider)
            _snackBarMessage.value = "Provider updated."
        }
    }

    fun deleteProvider(provider: ProviderEntity) {
        viewModelScope.launch {
            database.providerDao().deleteProvider(provider)
            _snackBarMessage.value = "Provider deleted."
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

    fun clearSnackBar() {
        _snackBarMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        voiceAssistant.shutdown()
    }
}
