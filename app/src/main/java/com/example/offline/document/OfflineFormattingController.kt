package com.example.offline.document

import com.example.data.model.DocumentEntity
import com.example.data.model.TemplateEntity
import com.example.offline.FormattingIntelligenceEngine
import com.example.offline.FormattingPreview
import com.example.offline.PasteDetectionService
import com.example.offline.PasteEvent
import com.example.offline.PreviewBuilder
import com.example.offline.RuleBasedFormattingEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class OfflinePhase { IDLE, ANALYZING, PREVIEW, GENERATING, READY, SAVING }

/** Everything the Chat screen shows for the Offline Formatting Agent. */
data class OfflineFormattingUiState(
    /** Long paste waiting for "Format Offline" or "Dismiss". */
    val suggestion: PasteEvent? = null,
    val phase: OfflinePhase = OfflinePhase.IDLE,
    val preview: FormattingPreview? = null,
    val format: OfflineFormat = OfflineFormat.DOCX,
    val formats: List<OfflineFormat> = emptyList(),
    /** Templates of My Templates that fit [format]. */
    val templateOptions: List<TemplateEntity> = emptyList(),
    /** Template chosen by the user (or [TemplateMatcher.BUILT_IN_ID]); null = default template. */
    val selectedTemplateId: String? = null,
    val template: TemplateMatch? = null,
    /** Generated file waiting for "Save to Library". */
    val draft: OfflineDraft? = null
) {
    /** The preview / result dialog is open. */
    val dialogVisible: Boolean get() = phase != OfflinePhase.IDLE && phase != OfflinePhase.ANALYZING
}

sealed class OfflineMessage {
    data class Saved(val title: String) : OfflineMessage()
    data class TooLong(val maxCharacters: Int) : OfflineMessage()
    object NothingToFormat : OfflineMessage()
    object AnalysisFailed : OfflineMessage()
    object GenerationFailed : OfflineMessage()
    object SaveFailed : OfflineMessage()
    object Cancelled : OfflineMessage()
}

/**
 * Drives the Offline Formatting Agent in Chat: paste suggestion -> local analysis -> template
 * selection -> preview -> user confirms -> Document Engine -> user saves to the Library.
 *
 * It has no access to AI providers, the orchestrator, credentials or the network: its only
 * collaborators are the local [FormattingIntelligenceEngine], the template repository, the
 * document engines and the Library. One operation runs at a time; [cancel] stops it at any
 * step and deletes an unsaved file. Call every method from the main thread.
 */
class OfflineFormattingController(
    private val scope: CoroutineScope,
    private val matcher: TemplateMatcher,
    private val generator: OfflineDocumentGenerator,
    private val library: OfflineLibrary,
    private val engine: FormattingIntelligenceEngine = RuleBasedFormattingEngine(),
    private val pasteDetector: PasteDetectionService = PasteDetectionService(),
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onMessage: (OfflineMessage) -> Unit = {},
    private val onSaved: suspend (DocumentEntity, OfflineDraft) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(OfflineFormattingUiState())
    val state: StateFlow<OfflineFormattingUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** Composer edit. [inputEnabled] is false while voice input or a request uses the composer. */
    fun onComposerTextChanged(before: String, after: String, inputEnabled: Boolean) {
        val current = _state.value
        current.suggestion?.let { shown ->
            // The pasted text was removed (cleared, sent, or deleted): hide the suggestion.
            if (after.length < shown.text.trim().length) _state.value = current.copy(suggestion = null)
        }
        if (current.phase != OfflinePhase.IDLE) return
        val event = pasteDetector.onTextChanged(before, after, inputEnabled) ?: return
        pasteDetector.markHandled(event)
        _state.value = _state.value.copy(suggestion = event)
    }

    /** "Dismiss": hides the suggestion; the composer text is not touched. */
    fun dismissSuggestion() {
        _state.value = _state.value.copy(suggestion = null)
    }

    /**
     * "Format Offline": analyzes the pasted text on the device and opens the preview.
     * @param chatTemplateId template attached to the chat, used as the explicit template
     */
    fun formatOffline(chatTemplateId: String?) {
        val paste = _state.value.suggestion ?: return
        if (_state.value.phase != OfflinePhase.IDLE) return
        if (paste.text.length > RuleBasedFormattingEngine.MAX_CHARACTERS) {
            _state.value = OfflineFormattingUiState()
            onMessage(OfflineMessage.TooLong(RuleBasedFormattingEngine.MAX_CHARACTERS))
            return
        }
        _state.value = OfflineFormattingUiState(phase = OfflinePhase.ANALYZING, selectedTemplateId = chatTemplateId)
        job = scope.launch {
            try {
                val result = withContext(analysisDispatcher) { engine.analyze(paste.text) }
                if (result.blocks.isEmpty()) {
                    _state.value = OfflineFormattingUiState()
                    onMessage(OfflineMessage.NothingToFormat)
                    return@launch
                }
                val format = OfflineFormat.default(result)
                val selected = _state.value.selectedTemplateId
                _state.value = _state.value.copy(
                    phase = OfflinePhase.PREVIEW,
                    preview = PreviewBuilder.build(result),
                    format = format,
                    formats = OfflineFormat.available(result),
                    templateOptions = matcher.options(format),
                    template = matcher.match(format, selected)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = OfflineFormattingUiState()
                onMessage(OfflineMessage.AnalysisFailed)
            }
        }
    }

    fun selectFormat(format: OfflineFormat) {
        val s = _state.value
        if (s.phase != OfflinePhase.PREVIEW || format !in s.formats || format == s.format) return
        _state.value = s.copy(format = format)
        refreshTemplate()
    }

    /** @param templateId a template id, [TemplateMatcher.BUILT_IN_ID], or null for the default template */
    fun selectTemplate(templateId: String?) {
        val s = _state.value
        if (s.phase != OfflinePhase.PREVIEW) return
        _state.value = s.copy(selectedTemplateId = templateId)
        refreshTemplate()
    }

    private fun refreshTemplate() {
        if (job?.isActive == true) job?.cancel()
        job = scope.launch {
            val s = _state.value
            val options = matcher.options(s.format)
            val match = matcher.match(s.format, s.selectedTemplateId)
            if (_state.value.phase == OfflinePhase.PREVIEW) {
                _state.value = _state.value.copy(templateOptions = options, template = match)
            }
        }
    }

    /** "Apply Formatting": the user approved the preview; the file is generated (not saved yet). */
    fun applyFormatting() {
        val s = _state.value
        val preview = s.preview ?: return
        val template = s.template ?: return
        // The phase alone gates each step: it changes on the main thread when a step finishes.
        if (s.phase != OfflinePhase.PREVIEW) return
        _state.value = s.copy(phase = OfflinePhase.GENERATING)
        job = scope.launch {
            try {
                val draft = generator.generate(preview.result, s.format, template)
                _state.value = _state.value.copy(phase = OfflinePhase.READY, draft = draft)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(phase = OfflinePhase.PREVIEW, draft = null)
                onMessage(OfflineMessage.GenerationFailed)
            }
        }
    }

    /** "Save to Library": stores the approved file and ends the flow. */
    fun saveToLibrary() {
        val s = _state.value
        val draft = s.draft ?: return
        if (s.phase != OfflinePhase.READY) return
        _state.value = s.copy(phase = OfflinePhase.SAVING)
        job = scope.launch {
            try {
                val entity = library.save(draft)
                onMessage(OfflineMessage.Saved(entity.title))
                _state.value = OfflineFormattingUiState()
                // The document is in the Library; a failing chat note must not report a failed save.
                try {
                    onSaved(entity, draft)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                generator.discard(draft)
                _state.value = _state.value.copy(phase = OfflinePhase.PREVIEW, draft = null)
                onMessage(OfflineMessage.SaveFailed)
            }
        }
    }

    /** "Cancel" at any step: stops the work, deletes an unsaved file, nothing is saved. */
    fun cancel() {
        val s = _state.value
        val wasRunning = s.phase != OfflinePhase.IDLE
        job?.cancel()
        job = null
        generator.discard(s.draft)
        _state.value = OfflineFormattingUiState()
        if (wasRunning) onMessage(OfflineMessage.Cancelled)
    }
}
