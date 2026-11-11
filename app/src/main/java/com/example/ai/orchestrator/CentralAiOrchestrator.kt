package com.example.ai.orchestrator

import android.content.Context
import com.example.ai.providers.AiErrorKind
import com.example.ai.providers.AiException
import com.example.ai.providers.AiMessage
import com.example.ai.providers.AiProviderClient
import com.example.ai.providers.AiRequest
import com.example.ai.providers.AiRole
import com.example.ai.providers.Json
import com.example.ai.providers.ProviderManager
import com.example.core.delivery.DeliveryManager
import com.example.core.storage.DocumentFileNames
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.DocumentEntity
import com.example.data.model.ProviderEntity
import com.example.data.model.TemplateEntity
import com.example.document.DocumentFileValidator
import com.example.document.EngineResult
import com.example.document.docx.DocxEngine
import com.example.document.pdf.PdfEngine
import com.example.document.pptx.PptxEngine
import com.example.document.xlsx.XlsxEngine
import com.example.template.domain.TemplateProfile
import com.example.template.preview.TemplatePreviewData
import com.example.template.preview.TemplatePreviewEngine
import com.example.template.preview.TemplatePreviewResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class TaskLifecycleState {
    CREATED,
    ANALYZING,
    GENERATING,
    VALIDATING,
    RENDERING,
    SAVING,
    OPENING,
    COMPLETED,
    FAILED,
    NEEDS_CLARIFICATION
}

/** Result of one chat turn handled by the orchestrator. */
data class ChatTurnResult(
    val reply: String,
    val provider: ProviderEntity,
    val document: DocumentEntity? = null,
    val templateApplied: Boolean = false
)

/**
 * The Central AI Orchestrator. Every AI task in the app goes through it, and it always uses the
 * user's DEFAULT provider (ai_providers.isDefault, managed by [ProviderManager]) for
 * understanding, planning, content generation and validation. It never switches providers on
 * its own: if the default provider is missing or fails, an [AiException] is thrown and the UI
 * explains the problem so the user can decide what to do.
 *
 * Specialized providers: not used. The orchestrator has a single execution path through the
 * default provider; delegating sub-tasks to other providers requires an explicit configuration
 * that does not exist yet (see Phase 2 report).
 *
 * Document flow: plan (default provider) -> structured JSON content (default provider) ->
 * local schema validation, with one repair round by the default provider -> document engine ->
 * file validation -> Library + reply composed only after the file exists.
 */
class CentralAiOrchestrator(
    private val context: Context,
    private val database: SmartBubbleDatabase,
    private val deliveryManager: DeliveryManager,
    private val providerManager: ProviderManager
) {
    private val docxEngine = DocxEngine()
    private val pptxEngine = PptxEngine()
    private val xlsxEngine = XlsxEngine()
    private val pdfEngine = PdfEngine()
    private val previewEngine = TemplatePreviewEngine()

    private val _currentState = MutableStateFlow(TaskLifecycleState.CREATED)
    val currentState: StateFlow<TaskLifecycleState> = _currentState.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    // ------------------------------------------------------------------ chat + documents

    /**
     * Handles a user message in a conversation.
     * @param history earlier messages of the conversation (oldest first), without the new message
     * @param selectedTemplate template the user attached to the chat (optional)
     * @param forceFormat when set, a document of this format is produced without planning
     */
    suspend fun handleChatTurn(
        history: List<ContextMessage>,
        userMessage: String,
        uiLanguage: String,
        selectedTemplate: TemplateEntity?,
        forceFormat: DocumentFormat? = null
    ): ChatTurnResult = withContext(Dispatchers.IO) {
        try {
            _currentState.value = TaskLifecycleState.ANALYZING
            val (provider, client) = providerManager.defaultClient()
            val conversation = ConversationContext.build(history, userMessage)

            val plan: Plan = if (forceFormat != null) {
                Plan.Document(forceFormat, title = null, brief = userMessage, reply = null)
            } else {
                planTurn(client, conversation, uiLanguage, selectedTemplate)
            }

            when (plan) {
                is Plan.Chat -> {
                    _currentState.value = TaskLifecycleState.COMPLETED
                    ChatTurnResult(reply = plan.reply, provider = provider)
                }
                is Plan.Document -> generateDocument(client, provider, conversation, plan, uiLanguage, selectedTemplate)
            }
        } catch (e: Exception) {
            _currentState.value = TaskLifecycleState.FAILED
            throw e
        }
    }

    private sealed class Plan {
        data class Chat(val reply: String) : Plan()
        data class Document(val format: DocumentFormat, val title: String?, val brief: String, val reply: String?) : Plan()
    }

    private suspend fun planTurn(
        client: AiProviderClient,
        conversation: List<AiMessage>,
        uiLanguage: String,
        selectedTemplate: TemplateEntity?
    ): Plan {
        val templateNote = selectedTemplate?.let {
            "The user selected the ${it.fileType} template \"${it.name}\". If they ask for a document, prefer the ${it.fileType} format unless they ask for another one."
        }
        val response = client.complete(
            AiRequest(
                system = OrchestratorPrompts.planner(uiLanguage, templateNote),
                messages = conversation,
                expectJson = true,
                maxOutputTokens = 16_000
            )
        )
        val obj = Json.extractObject(response.text)
        if (obj == null) {
            // The model answered in plain text: that IS its answer (never fabricated).
            if (response.truncated) throw AiException(AiErrorKind.TRUNCATED_RESPONSE)
            return Plan.Chat(response.text.trim())
        }
        val intent = (obj["intent"] as? String)?.lowercase()
        val reply = (obj["reply"] as? String)?.trim().orEmpty()
        if (intent == "document") {
            val format = DocumentFormat.parse(obj["format"] as? String) ?: DocumentFormat.DOCX
            val brief = (obj["brief"] as? String)?.trim().orEmpty()
            return Plan.Document(format, (obj["title"] as? String)?.trim(), brief.ifEmpty { conversation.last().text }, reply.ifEmpty { null })
        }
        if (reply.isEmpty()) {
            if (response.truncated) throw AiException(AiErrorKind.TRUNCATED_RESPONSE)
            throw AiException(AiErrorKind.EMPTY_RESPONSE)
        }
        return Plan.Chat(reply)
    }

    private data class TemplateChoice(val profile: TemplateProfile, val file: File?, val entity: TemplateEntity?, val note: String?)

    /** Picks the template for [format]: the chat-selected one if it matches, else the default. */
    private suspend fun chooseTemplate(format: DocumentFormat, selected: TemplateEntity?): TemplateChoice {
        val wantedType = if (format == DocumentFormat.PDF) "DOCX" else format.name
        var note: String? = null
        val entity = when {
            selected != null && selected.fileType.equals(wantedType, ignoreCase = true) -> selected
            else -> {
                if (selected != null) {
                    note = "The selected template \"${selected.name}\" is a ${selected.fileType} template and cannot be applied to a ${format.name} file; your default ${format.name} template was used instead (if any)."
                }
                val dao = database.templateDao()
                when (format) {
                    DocumentFormat.PPTX -> dao.getDefaultPptTemplate()
                    DocumentFormat.XLSX -> dao.getDefaultExcelTemplate()
                    else -> dao.getDefaultWordTemplate()
                }
            }
        }
        if (entity == null) return TemplateChoice(TemplateProfile.defaultProfile(), null, null, note)
        val profile = TemplateProfile.fromJson(entity.profileJson)
        val file = File(entity.originalFilePath).takeIf { it.isFile && it.length() > 0 }
        if (file == null) {
            note = listOfNotNull(note, "The template file \"${entity.name}\" is missing; only its saved colors and fonts were used.").joinToString(" ")
        }
        if (format == DocumentFormat.PDF) {
            note = listOfNotNull(note, "PDF output uses the template's colors and margins only; its layout cannot be reproduced in PDF.").joinToString(" ")
            return TemplateChoice(profile, null, entity, note)
        }
        return TemplateChoice(profile, file, entity, note)
    }

    /** Short description of the template's real structure so the AI can fit its content. */
    private suspend fun describeTemplate(entity: TemplateEntity?): String? {
        entity ?: return null
        val data = when (val r = previewEngine.loadPreview(entity)) {
            is TemplatePreviewResult.Success -> r.data
            is TemplatePreviewResult.Fallback -> r.data
            is TemplatePreviewResult.Error -> null
        } ?: return "template \"${entity.name}\" (${entity.fileType})."
        val outline = when (data) {
            is TemplatePreviewData.DocxPreview ->
                "Word template \"${entity.name}\" with sections: " + data.sections.take(12).joinToString("; ") { it.heading }
            is TemplatePreviewData.PptxPreview ->
                "presentation template \"${entity.name}\" whose slides cover: " + data.slides.take(12).joinToString("; ") { it.title }
            is TemplatePreviewData.XlsxPreview ->
                "workbook template \"${entity.name}\" with sheets: " + data.sheets.take(8).joinToString("; ") { "${it.name} (${it.headers.joinToString(", ")})" }
        }
        return outline.take(1_500)
    }

    private suspend fun generateDocument(
        client: AiProviderClient,
        provider: ProviderEntity,
        conversation: List<AiMessage>,
        plan: Plan.Document,
        uiLanguage: String,
        selectedTemplate: TemplateEntity?
    ): ChatTurnResult {
        _currentState.value = TaskLifecycleState.GENERATING
        val template = chooseTemplate(plan.format, selectedTemplate)
        val templateDescription = describeTemplate(template.entity)

        val writerMessages = conversation.dropLast(1) + AiMessage(
            AiRole.USER,
            buildString {
                append(conversation.last().text)
                append("\n\n[Document brief] ").append(plan.brief)
                plan.title?.let { append("\n[Suggested title] ").append(it) }
            }
        )
        val date = DateFormat.getDateInstance(DateFormat.LONG, Locale.forLanguageTag(uiLanguage)).format(Date())
        val parsed = requestStructuredContent(client, writerMessages, plan.format, uiLanguage, templateDescription, date)

        // Render the real file.
        _currentState.value = TaskLifecycleState.RENDERING
        val documentsDir = File(context.filesDir, "generated_docs").apply { mkdirs() }
        val outputFile = DocumentFileNames.createUniqueFile(documentsDir, parsed.document.title, plan.format.extension)
        var saved = false
        try {
            val engineResult: EngineResult = when (val doc = parsed.document) {
                is ParsedDocument.Word -> if (plan.format == DocumentFormat.PDF) {
                    pdfEngine.generatePdf(doc.model, outputFile, template.profile)
                    EngineResult(outputFile, templateApplied = false)
                } else {
                    docxEngine.generateDocxDetailed(doc.model, outputFile, template.profile, template.file)
                }
                is ParsedDocument.Presentation -> pptxEngine.generatePptxDetailed(doc.model, outputFile, template.profile, template.file)
                is ParsedDocument.Spreadsheet -> xlsxEngine.generateXlsxDetailed(doc.model, outputFile, template.profile, template.file)
            }

            _currentState.value = TaskLifecycleState.VALIDATING
            DocumentFileValidator.validate(outputFile, plan.format.extension)?.let { problem ->
                throw IllegalStateException(problem)
            }

            _currentState.value = TaskLifecycleState.SAVING
            val visibleTitle = DocumentFileNames.displayTitle(parsed.document.title) + "." + plan.format.extension
            val plain = parsed.document.plainText
            val counts = countsOf(parsed.document)
            val docEntity = DocumentEntity(
                id = UUID.randomUUID().toString(),
                title = visibleTitle,
                fileType = plan.format.name,
                filePath = outputFile.absolutePath,
                fileSize = outputFile.length(),
                wordCount = plain.split(Regex("\\s+")).count { it.isNotBlank() },
                pageOrSlideCount = counts,
                templateId = template.entity?.id,
                templateName = template.entity?.name,
                providerUsed = provider.name,
                summary = "${provider.name} • ${provider.modelName} • ${outputFile.length() / 1024} KB",
                textContent = plain,
                isOrchestratorVerified = true
            )
            database.documentDao().insertDocument(docEntity)
            saved = true

            _currentState.value = TaskLifecycleState.OPENING
            deliveryManager.deliverBoth(outputFile, plain, mimeTypeFor(plan.format), docEntity.title, openImmediately = false)

            // The reply is composed only now that the file exists and was validated.
            val notes = (listOfNotNull(template.note) + engineResult.notes + parsed.warnings).distinct()
            val reply = buildString {
                plan.reply?.let { append(it).append("\n\n") }
                append("**").append(docEntity.title).append("** (").append(outputFile.length() / 1024).append(" KB)")
                if (engineResult.templateApplied && template.entity != null) {
                    append(" — template: ").append(template.entity.name)
                }
                if (notes.isNotEmpty()) {
                    append("\n\n")
                    notes.forEach { append("• ").append(it).append('\n') }
                }
            }.trim()
            _currentState.value = TaskLifecycleState.COMPLETED
            return ChatTurnResult(reply, provider, docEntity, engineResult.templateApplied)
        } finally {
            if (!saved) outputFile.delete()
        }
    }

    /** Asks for structured JSON, validates it, and gives the default provider one repair round. */
    private suspend fun requestStructuredContent(
        client: AiProviderClient,
        messages: List<AiMessage>,
        format: DocumentFormat,
        uiLanguage: String,
        templateDescription: String?,
        date: String
    ): DocumentSchemaParser.Result {
        val system = OrchestratorPrompts.documentWriter(format, uiLanguage, templateDescription)
        val first = client.complete(AiRequest(system, messages, expectJson = true, maxOutputTokens = 16_000))
        val firstError = try {
            if (first.truncated) "The JSON was cut off because it was too long; make it more concise."
            else return DocumentSchemaParser.parse(first.text, format, date)
        } catch (e: DocumentSchemaException) {
            e.message ?: "Invalid structure."
        }

        _currentState.value = TaskLifecycleState.VALIDATING
        val repairMessages = messages + AiMessage(AiRole.ASSISTANT, first.text.take(20_000)) +
            AiMessage(AiRole.USER, OrchestratorPrompts.repair(firstError))
        val second = client.complete(AiRequest(system, repairMessages, expectJson = true, maxOutputTokens = 16_000))
        if (second.truncated) throw AiException(AiErrorKind.TRUNCATED_RESPONSE)
        return try {
            DocumentSchemaParser.parse(second.text, format, date)
        } catch (e: DocumentSchemaException) {
            throw AiException(AiErrorKind.MALFORMED_RESPONSE, e.message)
        }
    }

    private fun countsOf(doc: ParsedDocument): Int = when (doc) {
        is ParsedDocument.Word -> doc.model.sections.size.coerceAtLeast(1)
        is ParsedDocument.Presentation -> doc.model.slides.size + 1 // + title slide
        is ParsedDocument.Spreadsheet -> doc.model.sheets.size
    }

    private fun mimeTypeFor(format: DocumentFormat) = when (format) {
        DocumentFormat.DOCX -> DeliveryManager.MIME_DOCX
        DocumentFormat.PPTX -> DeliveryManager.MIME_PPTX
        DocumentFormat.XLSX -> DeliveryManager.MIME_XLSX
        DocumentFormat.PDF -> DeliveryManager.MIME_PDF
    }

    // ------------------------------------------------------------------ content operations

    /**
     * Rewrite / summarize / translate / correct / format / expand / shorten / answer questions
     * about [content], using the default provider. Returns the provider's actual output.
     */
    suspend fun performContentOperation(
        operation: ContentOperation,
        content: String,
        uiLanguage: String,
        instruction: String? = null,
        targetLanguage: String? = null
    ): Pair<String, ProviderEntity> = withContext(Dispatchers.IO) {
        if (content.isBlank() && operation != ContentOperation.GENERATE) {
            throw AiException(AiErrorKind.BAD_REQUEST, "No content to process")
        }
        val (provider, client) = providerManager.defaultClient()
        val user = buildString {
            if (!instruction.isNullOrBlank()) append(instruction.trim()).append("\n\n")
            if (content.isNotBlank()) append("--- CONTENT ---\n").append(content.take(MAX_CONTENT_CHARS))
        }
        val response = client.complete(
            AiRequest(
                system = OrchestratorPrompts.contentOperation(operation, uiLanguage, targetLanguage),
                messages = listOf(AiMessage(AiRole.USER, user)),
                maxOutputTokens = 16_000
            )
        )
        val text = response.text.trim()
        if (text.isEmpty()) throw AiException(AiErrorKind.EMPTY_RESPONSE)
        (if (response.truncated) "$text\n\n…" else text) to provider
    }

    /** AI clean-up of OCR text from one scanned page (default provider). */
    suspend fun enhanceScannedPageText(rawExtractedText: String): String = withContext(Dispatchers.IO) {
        if (rawExtractedText.isBlank()) return@withContext rawExtractedText
        val (_, client) = providerManager.defaultClient()
        val response = client.complete(
            AiRequest(
                system = OrchestratorPrompts.OCR_CLEANUP,
                messages = listOf(AiMessage(AiRole.USER, rawExtractedText.take(MAX_CONTENT_CHARS))),
                maxOutputTokens = 16_000
            )
        )
        if (response.truncated) throw AiException(AiErrorKind.TRUNCATED_RESPONSE)
        response.text.trim().ifEmpty { throw AiException(AiErrorKind.EMPTY_RESPONSE) }
    }

    private companion object {
        const val MAX_CONTENT_CHARS = 60_000
    }
}
