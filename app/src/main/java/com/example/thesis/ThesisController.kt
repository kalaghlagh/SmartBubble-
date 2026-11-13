package com.example.thesis

import com.example.ai.orchestrator.AiProgressListener
import com.example.ai.orchestrator.AiProgressStage
import com.example.ai.providers.AiException
import com.example.data.model.ThesisProjectEntity
import com.example.data.model.ThesisProposalEntity
import com.example.data.model.ThesisRevisionEntity
import com.example.thesis.ai.ThesisAi
import com.example.thesis.ai.ThesisAiFormatException
import com.example.thesis.core.DuplicateMatch
import com.example.thesis.core.EditMode
import com.example.thesis.core.NewElement
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream

/** ChatMessageEntity.attachedDocType of a Thesis Builder proposal card in Chat. */
const val THESIS_PROPOSAL_CARD = "THESIS_PROPOSAL"

enum class ThesisScreen { HOME, CREATE, PROJECT, SECTION, HISTORY, PREVIEW, SETTINGS }

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
data class RevisionCompare(val revision: ThesisRevisionEntity, val old: ThesisSnapshot, val current: ThesisSnapshot, val diff: ThesisDiffResult)

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
    val lastExport: ThesisExport? = null
)

/** Messages for the user (localized by the UI). */
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
    private val onSaveToLibrary: suspend (ThesisExport, String) -> String = { e, _ -> e.file.name }
) {
    private val _state = MutableStateFlow(ThesisUiState())
    val state: StateFlow<ThesisUiState> = _state.asStateFlow()

    val projects: StateFlow<List<ThesisProjectEntity>> = repository.observeProjects()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
    fun show(screen: ThesisScreen) = update { it.copy(screen = screen, compare = if (screen == ThesisScreen.HISTORY) it.compare else null) }

    fun openProject(projectId: String, screen: ThesisScreen = ThesisScreen.PROJECT) {
        projectIdFlow.value = projectId
        update { it.copy(projectId = projectId, workspaceOpen = true, screen = screen, previewFile = null, selectedSectionId = null) }
        scope.launch { reload() }
    }

    fun selectSection(sectionId: String) = update { it.copy(selectedSectionId = sectionId, screen = ThesisScreen.SECTION) }

    private suspend fun reload() {
        val id = _state.value.projectId ?: return
        val snapshot = repository.snapshot(id)
        val assets = repository.assets(id)
        update { it.copy(snapshot = snapshot, assets = assets) }
    }

    /** Chat sends messages to this project until [exitChatMode]. */
    fun enterChatMode(projectId: String) {
        if (_state.value.projectId != projectId) openProject(projectId)
        update { it.copy(chatProjectId = projectId, workspaceOpen = false) }
    }

    fun exitChatMode() = update { it.copy(chatProjectId = null) }

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
            update { it.copy(projectId = null, snapshot = null, screen = ThesisScreen.HOME, review = null, compare = null) }
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

    private suspend fun addContent(projectId: String, aiAssist: Boolean, snapshot: ThesisSnapshot, target: ThesisTarget?, content: String, request: String) {
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
        val title = onSaveToLibrary(export, id)
        update { it.copy(lastExport = export) }
        onMessage(ThesisMessage.Exported(title, export.template.notes + export.warnings))
    }
}
