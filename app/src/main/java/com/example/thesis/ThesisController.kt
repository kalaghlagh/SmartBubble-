package com.example.thesis

import com.example.ai.orchestrator.AiProgressListener
import com.example.ai.orchestrator.AiProgressStage
import com.example.ai.providers.AiException
import com.example.data.model.DocumentEntity
import com.example.data.model.ThesisProjectEntity
import com.example.data.model.ThesisProposalEntity
import com.example.data.model.ThesisRevisionEntity
import com.example.thesis.ai.ThesisAi
import com.example.thesis.ai.ThesisAiFormatException
import com.example.thesis.core.DuplicateMatch
import com.example.thesis.core.EditMode
import com.example.thesis.core.NewElement
import com.example.thesis.core.SectionDraft
import com.example.thesis.core.SectionText
import com.example.thesis.core.DraftMedia
import com.example.thesis.core.ThesisCommandParser
import com.example.thesis.core.ThesisContentSplitter
import com.example.thesis.core.ThesisDiff
import com.example.thesis.core.ThesisDiffResult
import com.example.thesis.core.ThesisDuplicates
import com.example.thesis.core.ThesisIntent
import com.example.thesis.core.ThesisOp
import com.example.thesis.core.ThesisOperations
import com.example.thesis.core.ThesisStructure
import com.example.thesis.core.ThesisTarget
import com.example.thesis.data.AcceptResult
import com.example.thesis.data.ThesisRepository
import com.example.thesis.export.ThesisExport
import com.example.thesis.export.ThesisExporter
import com.example.thesis.model.ChangeSource
import com.example.thesis.model.ElementData
import com.example.thesis.model.ElementType
import com.example.thesis.model.ProposalStatus
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisAsset
import com.example.thesis.model.ThesisElement
import com.example.thesis.model.ThesisMeta
import com.example.thesis.model.ThesisSection
import com.example.thesis.model.ThesisSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream

/** ChatMessageEntity.attachedDocType of a Thesis Builder proposal card in Chat. */
const val THESIS_PROPOSAL_CARD = "THESIS_PROPOSAL"

/**
 * Screens of the Thesis Builder: HOME (projects), CREATE (new thesis), PROJECT (workspace with the
 * section list), SECTION (section editor), AI (AI assistant for the open section) and PREVIEW.
 * CHANGES, HISTORY and SETTINGS are opened from the workspace menu.
 */
enum class ThesisScreen { HOME, CREATE, PROJECT, SECTION, AI, PREVIEW, CHANGES, HISTORY, SETTINGS }

/** A document exported and saved to the Library (opened or shared from the workspace). */
data class ThesisExportResult(val format: String, val document: DocumentEntity, val notes: List<String>)

enum class ThesisBusy { ANALYZING, ASKING_AI, EXPORTING, PREVIEWING, SAVING }

/** A proposal opened for review: approved content, proposed content and their differences. */
data class ProposalReview(
    val proposal: ThesisProposalEntity,
    val before: ThesisSnapshot,
    val after: ThesisSnapshot?,
    val diff: ThesisDiffResult?,
    /** Sections edited since the proposal was prepared. */
    val conflicts: List<String>,
    val duplicates: List<DuplicateMatch>
)

/** A past revision compared with the current content. */
data class RevisionCompare(
    val revision: ThesisRevisionEntity,
    val old: ThesisSnapshot,
    val current: ThesisSnapshot,
    val diff: ThesisDiffResult,
    /** true: the changes made by [revision]; false: [revision] compared with the current version. */
    val againstPrevious: Boolean = false
)

data class ThesisUiState(
    val workspaceOpen: Boolean = false,
    val screen: ThesisScreen = ThesisScreen.HOME,
    val projectId: String? = null,
    val snapshot: ThesisSnapshot? = null,
    val assets: List<ThesisAsset> = emptyList(),
    val selectedSectionId: String? = null,
    /** Project whose content Chat messages are sent to (thesis mode in Chat). */
    val chatProjectId: String? = null,
    val busy: ThesisBusy? = null,
    val review: ProposalReview? = null,
    val compare: RevisionCompare? = null,
    val previewFile: File? = null,
    val previewPages: Int = 0,
    val lastExport: ThesisExportResult? = null,
    /** Section that Chat content goes to when a message names no destination (thesis mode). */
    val chatSectionId: String? = null,
    /** Revisions in which the open section changed (section history). */
    val sectionHistory: List<ThesisRevisionEntity>? = null,
    /** Text of the section open in the editor (saved with "Save changes"). */
    val draft: SectionDraft? = null,
    /** AI assistant request and result for the open section. */
    val assist: AiAssist? = null
)

/** Messages for the user (localized by the UI). */
/**
 * One AI assistant request on the section being edited. [start]..[end] is the selected range of
 * the draft ([start] == [end]: the whole section). The result is only shown: it changes the draft
 * when the user inserts it, and the thesis when the user saves.
 */
data class AiAssist(
    val source: String,
    val start: Int,
    val end: Int,
    val mode: EditMode? = null,
    val instruction: String = "",
    val result: String? = null,
    val warnings: List<String> = emptyList(),
    val failed: Boolean = false,
    val unchanged: Boolean = false
) {
    val hasSelection: Boolean get() = end > start
}

sealed class ThesisMessage {
    data class Created(val title: String) : ThesisMessage()
    data class Applied(val revision: Int) : ThesisMessage()
    object Rejected : ThesisMessage()
    data class Conflict(val sections: Int) : ThesisMessage()
    data class Restored(val revision: Int) : ThesisMessage()
    data class Exported(val title: String, val notes: List<String>) : ThesisMessage()
    data class AiFailed(val error: AiException) : ThesisMessage()
    object AiInvalid : ThesisMessage()
    object Cancelled : ThesisMessage()
    data class ImageFailed(val reason: String) : ThesisMessage()
    data class Failed(val what: String) : ThesisMessage()
    object Deleted : ThesisMessage()
    object NothingToFormat : ThesisMessage()
    object Saved : ThesisMessage()
    object Renamed : ThesisMessage()
}

/**
 * Notes posted into the thesis conversation in Chat. [proposalId] adds a "Review" button.
 */
data class ThesisChatNote(val projectId: String, val text: ThesisNoteText, val proposalId: String? = null)

/** Note content as codes + arguments (localized by the app). */
data class ThesisNoteText(val code: String, val args: List<String> = emptyList())

/**
 * Coordinates the Thesis Builder: projects, Chat submissions, proposals and their review,
 * history, preview and export. The approved content is only changed by [accept] (proposals), by
 * the user's own edits in the workspace, or by [restore]; every change becomes a revision.
 *
 * Responsibilities are separated: deterministic formatting (splitting pasted text, tables, lists,
 * numbering, indexes, documents) is local; only explicit semantic requests (edit, table from
 * free data, destination/placement suggestions when none was named and AI assistance is on) go
 * to the default AI provider through [ai].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThesisController(
    private val scope: CoroutineScope,
    private val repository: ThesisRepository,
    private val ai: ThesisAi,
    private val exporter: ThesisExporter,
    private val previewDir: File,
    private val splitter: ThesisContentSplitter = ThesisContentSplitter(),
    private val onMessage: (ThesisMessage) -> Unit = {},
    private val onChatNote: suspend (ThesisChatNote) -> Unit = {},
    /** Moves an export into the Library and returns its Library entry. */
    private val onSaveToLibrary: suspend (ThesisExport, String) -> DocumentEntity
) {
    private val _state = MutableStateFlow(ThesisUiState())
    val state: StateFlow<ThesisUiState> = _state.asStateFlow()

    val projects: StateFlow<List<ThesisProjectEntity>> = repository.observeProjects()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Workspace progress (0..100) of every project, for the projects list. */
    val projectProgress: StateFlow<Map<String, Int>> = projects
        .mapLatest { list -> list.associate { p -> p.id to (try { ThesisStructure.progress(repository.snapshot(p.id)) } catch (e: Exception) { 0 }) } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val projectIdFlow = MutableStateFlow<String?>(null)
    val revisions: StateFlow<List<ThesisRevisionEntity>> = projectIdFlow
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.observeRevisions(id) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val openProposals: StateFlow<List<ThesisProposalEntity>> = projectIdFlow
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.observeOpenProposals(id) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _progress = MutableStateFlow<AiProgressStage?>(null)
    val progress: StateFlow<AiProgressStage?> = _progress.asStateFlow()

    private var job: Job? = null

    private fun update(f: (ThesisUiState) -> ThesisUiState) { _state.value = f(_state.value) }

    /** Runs one operation at a time; the UI disables actions while [ThesisUiState.busy] is set. */
    private fun launch(busy: ThesisBusy, block: suspend CoroutineScope.() -> Unit) {
        if (job?.isActive == true) return
        update { it.copy(busy = busy) }
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                onMessage(ThesisMessage.AiFailed(e))
            } catch (e: ThesisAiFormatException) {
                onMessage(ThesisMessage.AiInvalid)
            } catch (e: Exception) {
                onMessage(ThesisMessage.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                _progress.value = null
                update { it.copy(busy = null) }
            }
        }
    }

    private val listener = object : AiProgressListener {
        override fun onStage(stage: AiProgressStage) { _progress.value = stage }
    }

    /** Stop button: cancels the running AI request or export. Nothing is applied. */
    fun cancel() {
        val j = job ?: return
        if (!j.isActive) return
        j.cancel(CancellationException("Stopped by the user"))
        job = null
        _progress.value = null
        update { it.copy(busy = null) }
        onMessage(ThesisMessage.Cancelled)
    }

    // ------------------------------------------------------------------ navigation

    fun openWorkspace() = update { it.copy(workspaceOpen = true, screen = if (it.projectId != null) ThesisScreen.PROJECT else ThesisScreen.HOME) }
    fun closeWorkspace() = update { it.copy(workspaceOpen = false, review = null, compare = null) }
    fun show(screen: ThesisScreen) = update {
        it.copy(screen = screen, compare = if (screen == ThesisScreen.HISTORY) it.compare else null, sectionHistory = if (screen == ThesisScreen.SECTION) it.sectionHistory else null)
    }

    /**
     * One level up: AI assistant -> editor -> workspace -> projects -> close. Leaving the editor
     * saves unsaved text first (nothing typed is lost).
     */
    fun back() {
        val st = _state.value
        when (st.screen) {
            ThesisScreen.HOME -> closeWorkspace()
            ThesisScreen.CREATE -> show(ThesisScreen.HOME)
            ThesisScreen.PROJECT -> show(ThesisScreen.HOME)
            ThesisScreen.AI -> update { it.copy(screen = ThesisScreen.SECTION) }
            ThesisScreen.SECTION -> if (st.draft?.dirty == true) saveDraft(thenShow = ThesisScreen.PROJECT) else update { it.copy(screen = ThesisScreen.PROJECT, draft = null, assist = null) }
            else -> show(ThesisScreen.PROJECT)
        }
    }

    fun openProject(projectId: String, screen: ThesisScreen = ThesisScreen.PROJECT) {
        projectIdFlow.value = projectId
        val same = _state.value.projectId == projectId
        update { it.copy(projectId = projectId, workspaceOpen = true, screen = screen, previewFile = if (same) it.previewFile else null, selectedSectionId = null,
            draft = if (same) it.draft else null, assist = null, lastExport = if (same) it.lastExport else null) }
        scope.launch { reload() }
    }

    /** Opens the section editor with the section's saved text (an unsaved draft of it is kept). */
    fun selectSection(sectionId: String) {
        val st = _state.value
        val snapshot = st.snapshot
        val draft = st.draft?.takeIf { it.sectionId == sectionId } ?: snapshot?.let { SectionText.load(it, sectionId) }
        update { it.copy(selectedSectionId = sectionId, screen = ThesisScreen.SECTION, sectionHistory = null, draft = draft, assist = null) }
    }

    private suspend fun reload() {
        val id = _state.value.projectId ?: return
        val snapshot = repository.snapshot(id)
        val assets = repository.assets(id)
        update { st ->
            // The editor was opened before the snapshot was loaded: load its text now.
            val draft = st.draft ?: st.selectedSectionId?.takeIf { st.screen == ThesisScreen.SECTION }?.let { SectionText.load(snapshot, it) }
            st.copy(snapshot = snapshot, assets = assets, draft = draft)
        }
    }

    /**
     * Chat sends messages to this project until [exitChatMode]. [sectionId] is the target section:
     * content whose message names no destination goes there (still as a proposal to review).
     */
    fun enterChatMode(projectId: String, sectionId: String? = null) {
        if (_state.value.projectId != projectId) openProject(projectId)
        update { it.copy(chatProjectId = projectId, chatSectionId = sectionId, workspaceOpen = false) }
    }

    fun exitChatMode() = update { it.copy(chatProjectId = null, chatSectionId = null) }

    // ------------------------------------------------------------------ projects

    fun createProject(meta: ThesisMeta, aiAssist: Boolean) = launch(ThesisBusy.SAVING) {
        val id = repository.createProject(meta, aiAssist)
        onMessage(ThesisMessage.Created(meta.title))
        openProject(id)
    }

    fun updateMeta(meta: ThesisMeta) = direct(listOf(ThesisOp.UpdateMeta(meta)), ChangeSource.USER, "metadata")

    fun setAiAssist(enabled: Boolean) = scope.launch { _state.value.projectId?.let { repository.setAiAssist(it, enabled) } }

    fun deleteProject(projectId: String) = launch(ThesisBusy.SAVING) {
        repository.deleteProject(projectId)
        if (_state.value.chatProjectId == projectId) exitChatMode()
        if (_state.value.projectId == projectId) {
            projectIdFlow.value = null
            update { it.copy(projectId = null, snapshot = null, screen = ThesisScreen.HOME, review = null, compare = null, draft = null, assist = null, lastExport = null) }
        }
        onMessage(ThesisMessage.Deleted)
    }

    /** The user's own edit in the workspace: applied directly as a revision (recorded in history). */
    fun direct(ops: List<ThesisOp>, source: ChangeSource, summary: String) = launch(ThesisBusy.SAVING) {
        val id = _state.value.projectId ?: return@launch
        val revision = repository.applyDirect(id, ops, source, summary)
        reload()
        update { it.copy(previewFile = null) }
        onMessage(ThesisMessage.Applied(revision.number))
    }

    fun importLogo(open: () -> InputStream?, name: String, mime: String) = launch(ThesisBusy.SAVING) {
        val id = _state.value.projectId ?: return@launch
        val asset = try { repository.importImage(id, open, name, mime) } catch (e: com.example.thesis.data.ThesisAssetException) {
            onMessage(ThesisMessage.ImageFailed(e.reason.name)); return@launch
        }
        val meta = repository.snapshot(id).meta.copy(logoAssetId = asset.id)
        repository.applyDirect(id, listOf(ThesisOp.UpdateMeta(meta)), ChangeSource.IMAGE, "logo")
        reload()
    }

    // ------------------------------------------------------------------ chat submissions

    private suspend fun note(code: String, vararg args: String, proposalId: String? = null) {
        val id = _state.value.chatProjectId ?: _state.value.projectId ?: return
        onChatNote(ThesisChatNote(id, ThesisNoteText(code, args.toList()), proposalId))
    }

    /** Position after which new content goes for a target ("after paragraph 3", "paragraph 2"). */
    private fun afterFor(target: ThesisTarget, snapshot: ThesisSnapshot): String? {
        val elements = snapshot.elementsOf(target.sectionId)
        val n = target.afterParagraph ?: target.paragraph?.minus(1)?.takeIf { it >= 0 } ?: return null
        if (n == 0) return ThesisOperations.AFTER_START
        val paragraphs = elements.filter { it.type != ElementType.HEADING }
        return paragraphs.getOrNull(n - 1)?.id ?: elements.lastOrNull()?.id
    }

    private fun paragraphAt(target: ThesisTarget, snapshot: ThesisSnapshot): ThesisElement? {
        val n = target.paragraph ?: return null
        val paragraphs = snapshot.elementsOf(target.sectionId).filter { it.type == ElementType.PARAGRAPH }
        return if (n == -1) paragraphs.lastOrNull() else paragraphs.getOrNull(n - 1)
    }

    private fun sectionTitle(snapshot: ThesisSnapshot, id: String?): String {
        val s = snapshot.section(id) ?: return ""
        return ThesisStructure.displayTitle(s, ThesisStructure.numbering(snapshot), snapshot.meta.language)
    }

    /**
     * Handles a Chat message in thesis mode: understands the instruction locally, prepares a
     * proposal (content is never applied here) and posts a note with a Review button.
     */
    fun submitFromChat(message: String) = launch(ThesisBusy.ANALYZING) {
        val projectId = _state.value.chatProjectId ?: return@launch
        val project = repository.project(projectId) ?: return@launch
        val snapshot = repository.snapshot(projectId)
        when (val intent = ThesisCommandParser.parse(message, snapshot)) {
            is ThesisIntent.AddContent -> addContent(projectId, project.aiAssist, snapshot, intent.target, intent.content, message)
            is ThesisIntent.ReplaceParagraph -> {
                val element = paragraphAt(intent.target, snapshot)
                if (element == null) { note("thesis_note_paragraph_missing", sectionTitle(snapshot, intent.target.sectionId)); return@launch }
                propose(projectId, listOf(ThesisOp.ReplaceText(element.id, intent.content.trim())), ChangeSource.USER, "EDIT", message, "local:replace")
            }
            is ThesisIntent.Edit -> edit(projectId, snapshot, intent.mode, intent.target, message)
            is ThesisIntent.AddChapter -> {
                val last = snapshot.children(null).lastOrNull { it.kind == SectionKind.CHAPTER || it.kind == SectionKind.INTRODUCTION }
                val section = ThesisSection(ThesisStructure.newId("sec"), null, SectionKind.CHAPTER, intent.title, 0)
                propose(projectId, listOf(ThesisOp.AddSection(section, last?.id)), ChangeSource.STRUCTURE, "STRUCTURE", message, "local:add_chapter")
            }
            is ThesisIntent.AddSection -> {
                val parent = intent.target?.let { snapshot.section(it.sectionId) }?.takeIf { it.kind.container }
                    ?: snapshot.outline(false).map { it.first }.lastOrNull { it.kind == SectionKind.CHAPTER }
                if (parent == null) { note("thesis_note_where"); return@launch }
                val kind = if (parent.kind == SectionKind.SECTION) SectionKind.SUBSECTION else SectionKind.SECTION
                val section = ThesisSection(ThesisStructure.newId("sec"), parent.id, kind, intent.title, 0)
                propose(projectId, listOf(ThesisOp.AddSection(section, snapshot.children(parent.id).lastOrNull()?.id)), ChangeSource.STRUCTURE, "STRUCTURE", message, "local:add_section")
            }
            is ThesisIntent.Rename -> propose(projectId, listOf(ThesisOp.UpdateSection(intent.target.sectionId, title = intent.title)), ChangeSource.STRUCTURE, "STRUCTURE", message, "local:rename")
            is ThesisIntent.MoveSection -> {
                val destination = snapshot.section(intent.destination.sectionId)
                if (destination == null || !destination.kind.container || destination.id == intent.target.sectionId) { note("thesis_note_where"); return@launch }
                propose(projectId, listOf(ThesisOp.MoveSection(intent.target.sectionId, destination.id, snapshot.children(destination.id).lastOrNull()?.id)),
                    ChangeSource.STRUCTURE, "STRUCTURE", message, "local:move")
            }
            is ThesisIntent.AddHeading -> {
                val target = intent.target ?: run { note("thesis_note_where"); return@launch }
                if (intent.text.isBlank()) { note("thesis_note_heading_text"); return@launch }
                val el = NewElement(ThesisStructure.newId("el"), ElementType.HEADING, intent.text, ElementData(headingLevel = 1))
                propose(projectId, listOf(ThesisOp.AddElements(target.sectionId, afterFor(target, snapshot), listOf(el))), ChangeSource.USER, "CONTENT", message, "local:heading")
            }
            is ThesisIntent.MakeTable -> makeTable(projectId, snapshot, intent.target, intent.data, message)
            ThesisIntent.RestorePrevious -> {
                val revisions = repository.revisions(projectId)
                val previous = revisions.getOrNull(1)
                if (previous == null) { note("thesis_note_no_previous"); return@launch }
                compareRevision(previous)
                update { it.copy(workspaceOpen = true, screen = ThesisScreen.HISTORY) }
                note("thesis_note_restore_review", previous.number.toString())
            }
            ThesisIntent.ShowHistory -> update { it.copy(workspaceOpen = true, screen = ThesisScreen.HISTORY) }
            ThesisIntent.Unknown -> note("thesis_note_unknown")
        }
    }

    private suspend fun propose(
        projectId: String,
        ops: List<ThesisOp>,
        source: ChangeSource,
        kind: String,
        request: String,
        explanation: String,
        warnings: List<String> = emptyList(),
        needsDestination: Boolean = false,
        noteCode: String = "thesis_note_change",
        noteArgs: List<String> = emptyList()
    ): ThesisProposalEntity {
        val p = repository.propose(projectId, ops, source, kind, request, explanation, warnings, needsDestination)
        note(noteCode, *noteArgs.toTypedArray(), proposalId = p.id)
        openReview(p.id)
        return p
    }

    private suspend fun addContent(projectId: String, aiAssist: Boolean, snapshot: ThesisSnapshot, named: ThesisTarget?, content: String, request: String) {
        // The section chosen when Chat was opened from the workspace applies when none is named.
        val target = named ?: _state.value.chatSectionId?.takeIf { id -> snapshot.section(id)?.let { it.enabled && it.kind.writable } == true }?.let { ThesisTarget(it) }
        // Local, word-for-word structuring by the Offline Formatting Agent.
        val elements = splitter.split(content)
        if (elements.isEmpty()) { note("thesis_note_unknown"); return }
        val duplicates = ThesisDuplicates.find(elements, snapshot)
        val warnings = duplicates.map { "duplicate:${it.newElementId}:${it.existingElementId}" }
        if (target != null) {
            val section = snapshot.section(target.sectionId)
            if (section == null || !section.kind.writable) { note("thesis_note_where"); return }
            propose(projectId, listOf(ThesisOp.AddElements(target.sectionId, afterFor(target, snapshot), elements)), ChangeSource.USER, "CONTENT", request,
                "local:named_destination", warnings, noteCode = "thesis_note_proposal", noteArgs = listOf(elements.size.toString(), sectionTitle(snapshot, target.sectionId)))
            return
        }
        // No destination named: ask the default provider (if allowed), never guess locally.
        val provider = if (aiAssist) ai.providerName() else null
        if (provider != null) {
            update { it.copy(busy = ThesisBusy.ASKING_AI) }
            note("thesis_note_sent_to_provider", provider)
            val suggestion = ai.suggestDestination(snapshot, content, "", listener)
            if (suggestion.sectionId != null && suggestion.confidence >= 0.5) {
                propose(projectId, listOf(ThesisOp.AddElements(suggestion.sectionId, null, elements)), ChangeSource.AI, "CONTENT", request,
                    "ai:" + suggestion.reason, warnings, noteCode = "thesis_note_suggested", noteArgs = listOf(sectionTitle(snapshot, suggestion.sectionId)))
                return
            }
            propose(projectId, listOf(ThesisOp.AddElements("", null, elements)), ChangeSource.USER, "CONTENT", request,
                "ask:" + suggestion.question, warnings, needsDestination = true, noteCode = "thesis_note_choose_destination")
            return
        }
        propose(projectId, listOf(ThesisOp.AddElements("", null, elements)), ChangeSource.USER, "CONTENT", request, "ask:", warnings,
            needsDestination = true, noteCode = "thesis_note_choose_destination")
    }

    private suspend fun edit(projectId: String, snapshot: ThesisSnapshot, mode: EditMode, target: ThesisTarget?, request: String) {
        val sectionId = target?.sectionId ?: _state.value.selectedSectionId
        if (sectionId == null) { note("thesis_note_which_section"); return }
        val elements = snapshot.elementsOf(sectionId).filter { it.type == ElementType.PARAGRAPH || it.type == ElementType.LIST_ITEM || it.type == ElementType.HEADING }
        val chosen = target?.let { paragraphAt(it, snapshot) }?.let { listOf(it) } ?: elements
        if (chosen.isEmpty()) { note("thesis_note_section_empty", sectionTitle(snapshot, sectionId)); return }
        val provider = ai.providerName() ?: throw AiException(com.example.ai.providers.AiErrorKind.NO_DEFAULT_PROVIDER)
        update { it.copy(busy = ThesisBusy.ASKING_AI) }
        note("thesis_note_sent_to_provider", provider)
        val result = ai.edit(snapshot, mode, chosen.map { it.id to it.text }, request, listener)
        if (result.edits.isEmpty()) { note("thesis_note_no_changes"); return }
        propose(projectId, result.edits.map { (id, text) -> ThesisOp.ReplaceText(id, text) }, ChangeSource.AI, "EDIT", request,
            "ai:" + result.notes, result.warnings, noteCode = "thesis_note_edit", noteArgs = listOf(result.edits.size.toString(), sectionTitle(snapshot, sectionId)))
    }

    private suspend fun makeTable(projectId: String, snapshot: ThesisSnapshot, target: ThesisTarget?, data: String, request: String) {
        if (data.isBlank()) { note("thesis_note_table_data"); return }
        // Clearly tabular text is converted locally; free-form data needs the provider.
        var element = splitter.split(data).firstOrNull { it.type == ElementType.TABLE }
        var explanation = "local:table"
        if (element == null) {
            val provider = ai.providerName() ?: throw AiException(com.example.ai.providers.AiErrorKind.NO_DEFAULT_PROVIDER)
            update { it.copy(busy = ThesisBusy.ASKING_AI) }
            note("thesis_note_sent_to_provider", provider)
            val table = ai.table(snapshot, data, listener)
            element = NewElement(ThesisStructure.newId("el"), ElementType.TABLE, "", ElementData(tableHeader = table.header, tableRows = table.rows))
            explanation = "ai:table"
        }
        val needs = target == null
        propose(projectId, listOf(ThesisOp.AddElements(target?.sectionId ?: "", target?.let { afterFor(it, snapshot) }, listOf(element))),
            ChangeSource.TABLE, "TABLE", request, explanation, needsDestination = needs,
            noteCode = if (needs) "thesis_note_choose_destination" else "thesis_note_table")
    }

    /** Images attached in Chat: stored in the project, then a placement proposal (caption typed by the user). */
    fun addImagesFromChat(images: List<Triple<() -> InputStream?, String, String>>, instruction: String) = launch(ThesisBusy.ANALYZING) {
        val projectId = _state.value.chatProjectId ?: return@launch
        val project = repository.project(projectId) ?: return@launch
        val snapshot = repository.snapshot(projectId)
        val elements = ArrayList<NewElement>()
        for ((open, name, mime) in images) {
            val asset = try { repository.importImage(projectId, open, name, mime) } catch (e: com.example.thesis.data.ThesisAssetException) {
                onMessage(ThesisMessage.ImageFailed(e.reason.name)); continue
            }
            elements.add(NewElement(ThesisStructure.newId("el"), ElementType.IMAGE, "", ElementData(assetId = asset.id)))
        }
        if (elements.isEmpty()) return@launch
        reload()
        val target = instruction.takeIf { it.isNotBlank() }?.let { ThesisCommandParser.resolveTarget(it, snapshot) }
        if (target != null) {
            propose(projectId, listOf(ThesisOp.AddElements(target.sectionId, afterFor(target, snapshot), elements)), ChangeSource.IMAGE, "IMAGE", instruction,
                "local:named_destination", noteCode = "thesis_note_image", noteArgs = listOf(sectionTitle(snapshot, target.sectionId)))
            return@launch
        }
        val provider = if (project.aiAssist && instruction.isNotBlank()) ai.providerName() else null
        if (provider != null) {
            update { it.copy(busy = ThesisBusy.ASKING_AI) }
            note("thesis_note_sent_to_provider", provider)
            val s = ai.suggestImagePlace(snapshot, instruction, listener)
            if (s.sectionId != null && s.confidence >= 0.5) {
                propose(projectId, listOf(ThesisOp.AddElements(s.sectionId, null, elements)), ChangeSource.IMAGE, "IMAGE", instruction,
                    "ai:" + s.reason, noteCode = "thesis_note_image", noteArgs = listOf(sectionTitle(snapshot, s.sectionId)))
                return@launch
            }
        }
        propose(projectId, listOf(ThesisOp.AddElements("", null, elements)), ChangeSource.IMAGE, "IMAGE", instruction, "ask:",
            needsDestination = true, noteCode = "thesis_note_choose_destination")
    }

    // ------------------------------------------------------------------ review

    fun openReview(proposalId: String) = scope.launch {
        val p = repository.proposal(proposalId) ?: return@launch
        if (_state.value.projectId != p.projectId) openProject(p.projectId)
        val (before, after) = repository.preview(p)
        val conflicts = if (p.status == ProposalStatus.PENDING.name || p.status == ProposalStatus.CONFLICT.name) repository.conflicts(p, before) else emptyList()
        val added = ThesisOperations.decode(p.payload).filterIsInstance<ThesisOp.AddElements>().flatMap { it.elements }
        update {
            it.copy(
                workspaceOpen = true,
                review = ProposalReview(p, before, after, after?.let { a -> ThesisDiff.compare(before, a) }, conflicts, ThesisDuplicates.find(added, before)),
                snapshot = before
            )
        }
    }

    fun closeReview() = update { it.copy(review = null) }

    /**
     * A proposal in conflict, after the user saw the differences against the CURRENT content:
     * prepared again on top of it (it becomes pending; nothing is applied yet).
     */
    fun recheck(proposalId: String) = launch(ThesisBusy.SAVING) {
        val p = repository.proposal(proposalId) ?: return@launch
        repository.updateProposal(proposalId, ThesisOperations.decode(p.payload))
        openReview(proposalId)
    }

    /** "Correct", "Improve"... requested from the workspace for one section. */
    fun requestEdit(mode: EditMode, sectionId: String) = launch(ThesisBusy.ANALYZING) {
        val projectId = _state.value.projectId ?: return@launch
        val snapshot = repository.snapshot(projectId)
        edit(projectId, snapshot, mode, ThesisTarget(sectionId), mode.name.lowercase())
    }

    /** Sets where pending content goes (destination chosen by the user). */
    fun setDestination(proposalId: String, sectionId: String, afterElementId: String?) = launch(ThesisBusy.SAVING) {
        val p = repository.proposal(proposalId) ?: return@launch
        val ops = ThesisOperations.decode(p.payload).map { op ->
            if (op is ThesisOp.AddElements) op.copy(sectionId = sectionId, afterElementId = afterElementId) else op
        }
        repository.updateProposal(proposalId, ops)
        openReview(proposalId)
    }

    /** Caption, source, size and alignment of images / caption of tables in a pending proposal. */
    fun editProposalElement(proposalId: String, elementId: String, data: ElementData) = launch(ThesisBusy.SAVING) {
        val p = repository.proposal(proposalId) ?: return@launch
        val ops = ThesisOperations.decode(p.payload).map { op ->
            if (op is ThesisOp.AddElements) op.copy(elements = op.elements.map { if (it.id == elementId) it.copy(data = data) else it }) else op
        }
        repository.updateProposalKeepingStatus(proposalId, ops)
        openReview(proposalId)
    }

    fun accept(proposalId: String) = launch(ThesisBusy.SAVING) {
        when (val r = repository.accept(proposalId)) {
            is AcceptResult.Accepted -> {
                reload()
                update { it.copy(review = null, previewFile = null) }
                onMessage(ThesisMessage.Applied(r.revision.number))
                note("thesis_note_applied", r.revision.number.toString())
            }
            is AcceptResult.Conflict -> {
                onMessage(ThesisMessage.Conflict(r.sectionIds.size))
                openReview(proposalId)
            }
        }
    }

    fun reject(proposalId: String) = launch(ThesisBusy.SAVING) {
        repository.reject(proposalId)
        update { it.copy(review = null) }
        onMessage(ThesisMessage.Rejected)
        note("thesis_note_rejected")
    }

    // ------------------------------------------------------------------ section editor

    fun updateDraft(text: String) = update { st -> st.copy(draft = st.draft?.copy(text = text)) }

    /**
     * Saves the editor text word for word: the Offline Formatting Agent splits it into paragraphs,
     * headings, lists and tables; unchanged text keeps its elements; figures stay where their
     * placeholder line is. One revision per save (history can restore any earlier one).
     */
    fun saveDraft(thenShow: ThesisScreen? = null) = launch(ThesisBusy.SAVING) {
        val projectId = _state.value.projectId ?: return@launch
        val draft = _state.value.draft ?: return@launch
        val snapshot = repository.snapshot(projectId)
        if (snapshot.section(draft.sectionId) == null) { onMessage(ThesisMessage.Failed("section")); return@launch }
        val ops = SectionText.plan(snapshot, draft) { splitter.split(it) }
        if (ops.isNotEmpty()) {
            repository.applyDirect(projectId, ops, ChangeSource.USER, "section")
            reload()
        }
        val saved = _state.value.snapshot ?: snapshot
        update { st ->
            val reloaded = SectionText.load(saved, draft.sectionId)
            // Text typed while saving is kept as an unsaved change.
            val current = st.draft
            val keepTyping = current != null && current.sectionId == draft.sectionId && current.text != draft.text
            st.copy(
                draft = if (thenShow != null) null else if (keepTyping) reloaded.copy(text = current!!.text) else reloaded,
                screen = thenShow ?: st.screen, previewFile = if (ops.isEmpty()) st.previewFile else null
            )
        }
        onMessage(ThesisMessage.Saved)
    }

    /** Leaves the editor without saving (the user confirmed discarding the changes). */
    fun discardDraft() = update { it.copy(draft = null, assist = null, screen = ThesisScreen.PROJECT) }

    /**
     * Imports a picked image into the project and inserts its placeholder (for example
     * "[Figure 2]") at [at] in the editor text. It becomes part of the thesis when the user saves.
     */
    fun insertDraftImage(open: () -> InputStream?, name: String, mime: String, data: ElementData, at: Int) = launch(ThesisBusy.SAVING) {
        val projectId = _state.value.projectId ?: return@launch
        val asset = try { repository.importImage(projectId, open, name, mime) } catch (e: com.example.thesis.data.ThesisAssetException) {
            onMessage(ThesisMessage.ImageFailed(e.reason.name)); return@launch
        }
        val assets = repository.assets(projectId)
        update { st ->
            val d = st.draft ?: return@update st
            val language = st.snapshot?.meta?.language ?: "en"
            val token = SectionText.nextToken(d, language, ElementType.IMAGE)
            val pos = at.coerceIn(0, d.text.length)
            val before = d.text.substring(0, pos).trimEnd('\n', ' ')
            val after = d.text.substring(pos).trimStart('\n', ' ')
            val text = listOf(before, token, after).filter { it.isNotEmpty() }.joinToString("\n\n")
            st.copy(assets = assets, draft = d.copy(text = text, media = d.media + (token to DraftMedia(null, ElementType.IMAGE, data.copy(assetId = asset.id))), mediaChanged = true))
        }
    }

    /** Changes the caption (and size) of a figure of the draft. */
    fun updateDraftMedia(token: String, data: ElementData) = update { st ->
        val d = st.draft ?: return@update st
        val m = d.media[token] ?: return@update st
        st.copy(draft = d.copy(media = d.media + (token to m.copy(data = data)), mediaChanged = true))
    }

    fun setSectionCompleted(sectionId: String, completed: Boolean) =
        direct(listOf(ThesisOp.UpdateSection(sectionId, completed = completed)), ChangeSource.STRUCTURE, if (completed) "completed" else "reopened")

    /** Renames a project from the projects list (a revision of that project). */
    fun renameProject(projectId: String, title: String) = launch(ThesisBusy.SAVING) {
        val t = title.trim()
        if (t.isEmpty()) return@launch
        val snapshot = repository.snapshot(projectId)
        repository.applyDirect(projectId, listOf(ThesisOp.UpdateMeta(snapshot.meta.copy(title = t))), ChangeSource.USER, "rename")
        if (_state.value.projectId == projectId) reload()
        onMessage(ThesisMessage.Renamed)
    }

    // ------------------------------------------------------------------ AI assistant (section editor)

    /** Opens the AI assistant on the selected text of the draft (or the whole section). */
    fun openAssistant(start: Int, end: Int, mode: EditMode? = null) {
        val d = _state.value.draft ?: return
        val a = start.coerceIn(0, d.text.length)
        val b = end.coerceIn(a, d.text.length)
        val source = if (b > a) d.text.substring(a, b) else d.text
        update { it.copy(screen = ThesisScreen.AI, assist = AiAssist(source, a, b)) }
        if (mode != null) runAssistant(mode, "")
    }

    fun setAssistInstruction(text: String) = update { st -> st.copy(assist = st.assist?.copy(instruction = text)) }

    /**
     * Sends the text to the default provider through the central orchestrator. The result is only
     * displayed; a failure leaves the user's text exactly as it was.
     */
    fun runAssistant(mode: EditMode, instruction: String) {
        val a = _state.value.assist ?: return
        if (mode != EditMode.GENERATE && a.source.isBlank()) return
        update { st -> st.copy(assist = a.copy(mode = mode, instruction = instruction, result = null, warnings = emptyList(), failed = false, unchanged = false)) }
        launch(ThesisBusy.ASKING_AI) {
            try {
                val snapshot = _state.value.snapshot ?: return@launch
                val r = ai.edit(snapshot, mode, listOf("text" to a.source), instruction.ifBlank { defaultInstruction(mode) }, listener)
                val text = r.edits["text"]
                update { st -> st.copy(assist = st.assist?.copy(result = text, warnings = r.warnings.map { it.substringBefore(':') }.distinct(), unchanged = text == null)) }
            } catch (e: CancellationException) {
                update { st -> st.copy(assist = st.assist?.copy(failed = false)) }
                throw e
            } catch (e: Exception) {
                update { st -> st.copy(assist = st.assist?.copy(failed = true)) }
                throw e // reported by launch() as a message; the draft is untouched
            }
        }
    }

    private fun defaultInstruction(mode: EditMode) = when (mode) {
        EditMode.GENERATE -> "Suggest content for this section."
        else -> "Apply the task to the text."
    }

    /**
     * Puts the AI result into the draft: [replace] replaces the selection (or the whole section),
     * otherwise the result is inserted after it. Figures and tables whose placeholder the result
     * dropped are kept at the end, so nothing is lost. The thesis changes only on "Save changes".
     */
    fun insertAssistResult(replace: Boolean) {
        val st = _state.value
        val a = st.assist ?: return
        val result = a.result ?: return
        val d = st.draft ?: return
        val text = d.text
        val newText = if (replace) {
            val replaced = if (a.hasSelection && a.end <= text.length) text.substring(0, a.start) + result + text.substring(a.end) else result
            val lost = d.media.keys.filter { token -> text.lines().any { it.trim() == token } && replaced.lines().none { it.trim() == token } }
            if (lost.isEmpty()) replaced else replaced.trimEnd() + "\n\n" + lost.joinToString("\n\n")
        } else {
            val at = (if (a.hasSelection) a.end else text.length).coerceIn(0, text.length)
            listOf(text.substring(0, at).trimEnd(), result.trim(), text.substring(at).trimStart()).filter { it.isNotEmpty() }.joinToString("\n\n")
        }
        update { it.copy(draft = d.copy(text = newText), assist = null, screen = ThesisScreen.SECTION) }
    }

    fun closeAssistant() = update { it.copy(assist = null, screen = ThesisScreen.SECTION) }

    // ------------------------------------------------------------------ section tools (workspace)

    /** Adds a heading the user typed to the open section (a revision; no AI). */
    fun addHeading(sectionId: String, text: String) {
        if (text.isBlank()) return
        direct(listOf(ThesisOp.AddElements(sectionId, null, listOf(NewElement(ThesisStructure.newId("el"), ElementType.HEADING, text.trim(), ElementData(headingLevel = 1))))),
            ChangeSource.USER, "heading")
    }

    /**
     * Adds text the user typed: split word for word into paragraphs, lists and tables by the
     * Offline Formatting Agent, then saved as a revision.
     */
    fun addText(sectionId: String, text: String) = launch(ThesisBusy.SAVING) {
        val id = _state.value.projectId ?: return@launch
        val elements = splitter.split(text)
        if (elements.isEmpty()) return@launch
        val revision = repository.applyDirect(id, listOf(ThesisOp.AddElements(sectionId, null, elements)), ChangeSource.USER, "text")
        reload()
        update { it.copy(previewFile = null) }
        onMessage(ThesisMessage.Applied(revision.number))
    }

    /** Inserts an image picked in the workspace, with the caption typed by the user. */
    fun addImage(sectionId: String, open: () -> InputStream?, name: String, mime: String, data: ElementData) = launch(ThesisBusy.SAVING) {
        val id = _state.value.projectId ?: return@launch
        val asset = try { repository.importImage(id, open, name, mime) } catch (e: com.example.thesis.data.ThesisAssetException) {
            onMessage(ThesisMessage.ImageFailed(e.reason.name)); return@launch
        }
        val element = NewElement(ThesisStructure.newId("el"), ElementType.IMAGE, "", data.copy(assetId = asset.id))
        val revision = repository.applyDirect(id, listOf(ThesisOp.AddElements(sectionId, null, listOf(element))), ChangeSource.IMAGE, "image")
        reload()
        update { it.copy(previewFile = null) }
        onMessage(ThesisMessage.Applied(revision.number))
    }

    /** Inserts a table the user typed (cells exactly as written). */
    fun addTable(sectionId: String, header: List<String>, rows: List<List<String>>, caption: String) {
        if (header.isEmpty()) return
        direct(listOf(ThesisOp.AddElements(sectionId, null, listOf(NewElement(ThesisStructure.newId("el"), ElementType.TABLE, "",
            ElementData(caption = caption.trim(), tableHeader = header, tableRows = rows))))), ChangeSource.TABLE, "table")
    }

    /**
     * "Apply formatting" (local, Offline Formatting Agent): paragraphs that contain lists,
     * headings or tables are split into those elements and spacing is normalized. The result is
     * a FORMATTING proposal to review; words are never changed.
     */
    fun formatSection(sectionId: String) = launch(ThesisBusy.ANALYZING) {
        val projectId = _state.value.projectId ?: return@launch
        val snapshot = repository.snapshot(projectId)
        val ops = ArrayList<ThesisOp>()
        for (e in snapshot.elementsOf(sectionId)) {
            if (e.type != ElementType.PARAGRAPH) continue
            val parts = splitter.split(e.text.replace("\r\n", "\n"))
            if (parts.size > 1 || (parts.size == 1 && parts[0].type != ElementType.PARAGRAPH)) {
                ops.add(ThesisOp.AddElements(sectionId, e.id, parts))
                ops.add(ThesisOp.DeleteElement(e.id))
            } else if (parts.size == 1 && parts[0].text != e.text) {
                ops.add(ThesisOp.ReplaceText(e.id, parts[0].text))
            }
        }
        if (ops.isEmpty()) { onMessage(ThesisMessage.NothingToFormat); return@launch }
        propose(projectId, ops, ChangeSource.FORMATTING, "FORMAT", "formatting", "local:format")
    }

    /** Revisions in which [sectionId] changed (newest first; the last 40 revisions are checked). */
    fun loadSectionHistory(sectionId: String) = launch(ThesisBusy.ANALYZING) {
        val projectId = _state.value.projectId ?: return@launch
        val revisions = repository.revisions(projectId).take(40)
        val changed = ArrayList<ThesisRevisionEntity>()
        var newer: String? = null
        for (r in revisions) {
            val hash = repository.revisionSnapshot(r.id).sectionHash(sectionId)
            if (newer != null && newer != hash) changed.add(revisions[revisions.indexOf(r) - 1])
            newer = hash
        }
        // Revision 1 created the project's first sections.
        revisions.lastOrNull()?.takeIf { it.number == 1 }?.let { first -> if (repository.revisionSnapshot(first.id).section(sectionId) != null) changed.add(first) }
        update { it.copy(sectionHistory = changed) }
    }

    /** Changes made BY [revision] (compared with the revision before it). */
    fun compareWithPrevious(revision: ThesisRevisionEntity) = scope.launch {
        val all = repository.revisions(revision.projectId)
        val previous = all.firstOrNull { it.number == revision.number - 1 } ?: return@launch
        val old = repository.revisionSnapshot(previous.id)
        val new = repository.revisionSnapshot(revision.id)
        update { it.copy(compare = RevisionCompare(revision, old, new, ThesisDiff.compare(old, new), againstPrevious = true), screen = ThesisScreen.HISTORY) }
    }

    // ------------------------------------------------------------------ history

    fun compareRevision(revision: ThesisRevisionEntity) = scope.launch {
        val old = repository.revisionSnapshot(revision.id)
        val current = repository.snapshot(revision.projectId)
        update { it.copy(compare = RevisionCompare(revision, old, current, ThesisDiff.compare(old, current)), screen = ThesisScreen.HISTORY) }
    }

    fun closeCompare() = update { it.copy(compare = null) }

    /** Restores [revision] as a new revision (later history is kept). */
    fun restore(revision: ThesisRevisionEntity) = launch(ThesisBusy.SAVING) {
        val r = repository.restore(revision.projectId, revision.id)
        reload()
        update { it.copy(compare = null, previewFile = null) }
        onMessage(ThesisMessage.Restored(revision.number))
        note("thesis_note_restored", revision.number.toString(), r.number.toString())
    }

    // ------------------------------------------------------------------ preview and export

    /** Renders the page preview (the same layout as the PDF export); reused while nothing changed. */
    fun buildPreview() = launch(ThesisBusy.PREVIEWING) {
        val id = _state.value.projectId ?: return@launch
        val project = repository.project(id) ?: return@launch
        previewDir.mkdirs()
        val name = "preview-${project.currentRevisionId ?: "0"}-${project.updatedAt}.pdf"
        val file = File(previewDir, name)
        if (!file.isFile) {
            previewDir.listFiles()?.forEach { it.delete() } // only one preview is kept
            val export = exporter.export(id, "PDF", name)
            update { it.copy(previewFile = export.file, previewPages = export.pages, screen = ThesisScreen.PREVIEW) }
        } else {
            update { it.copy(previewFile = file, screen = ThesisScreen.PREVIEW) }
        }
    }

    /** Exports DOCX or PDF and saves it to the Library (a new file every time; nothing is overwritten). */
    fun export(format: String) = launch(ThesisBusy.EXPORTING) {
        val id = _state.value.projectId ?: return@launch
        val export = exporter.export(id, format)
        val document = onSaveToLibrary(export, id)
        val notes = export.template.notes + export.warnings
        update { it.copy(lastExport = ThesisExportResult(format, document, notes)) }
        onMessage(ThesisMessage.Exported(document.title, notes))
    }
}
