package com.example.agent

import com.example.agent.core.AgentEngine
import com.example.agent.core.AgentIntent
import com.example.agent.core.AgentTask
import com.example.agent.core.AgentTaskCodec
import com.example.agent.core.AgentTaskStore
import com.example.agent.core.AgentTool
import com.example.agent.core.IntentAnalyzer
import com.example.agent.core.LibraryRef
import com.example.agent.core.ProducedDocument
import com.example.agent.core.ResultValidator
import com.example.agent.core.TableStats
import com.example.agent.core.TaskPlanner
import com.example.agent.core.ToolCall
import com.example.agent.core.ToolException
import com.example.agent.core.ToolPermission
import com.example.agent.core.ToolRegistry
import com.example.agent.core.ToolSpec
import com.example.agent.routing.ModelSupport
import com.example.agent.routing.PriorityEntry
import com.example.agent.routing.PriorityList
import com.example.agent.routing.ProviderCandidate
import com.example.agent.routing.RoutedAiClient
import com.example.agent.routing.RoutingEvent
import com.example.agent.routing.RoutingPolicy
import com.example.agent.routing.RoutingSession
import com.example.ai.orchestrator.ContextMessage
import com.example.ai.providers.AiErrorKind
import com.example.ai.providers.AiException
import com.example.ai.providers.ModelCatalogCache
import com.example.ai.providers.ProviderManager
import com.example.core.storage.PreferencesManager
import com.example.data.db.AgentTaskDao
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.AgentTaskEntity
import com.example.data.model.DocumentEntity
import com.example.data.model.ProviderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** What the Agent screen shows about provider routing for the running request. */
data class RoutingStatus(
    val providerName: String? = null,
    val model: String? = null,
    val cycle: Int = 1,
    /** Recent switching/skip events (newest last, at most [MAX_EVENTS]). */
    val events: List<RoutingEvent> = emptyList(),
    /** A pause before another pass over the list, in ms; null when not waiting. */
    val waitingMs: Long? = null
) {
    companion object {
        const val MAX_EVENTS = 4
    }
}

data class AgentUiState(
    /** The Agent Mode task of the current conversation that is still shown (null: none). */
    val task: AgentTask? = null,
    val routing: RoutingStatus? = null
)

/** Result of the content step, produced by the existing orchestrator pipeline. */
data class GeneratedContent(val reply: String, val document: DocumentEntity?, val templateApplied: Boolean, val templateRequested: Boolean)

/** Room-backed checkpoints (agent_tasks). */
class RoomAgentTaskStore(private val dao: AgentTaskDao) : AgentTaskStore {
    override suspend fun save(task: AgentTask) = withContext(Dispatchers.IO) {
        dao.upsert(AgentTaskEntity(task.id, task.sessionId, task.status.name, AgentTaskCodec.encode(task), task.createdAt, task.updatedAt))
    }

    override suspend fun load(id: String) = withContext(Dispatchers.IO) { dao.byId(id)?.let { AgentTaskCodec.decode(it.snapshotJson) } }
    override suspend fun forSession(sessionId: String) = withContext(Dispatchers.IO) {
        dao.forSession(sessionId).mapNotNull { AgentTaskCodec.decode(it.snapshotJson) }
    }

    override suspend fun unfinished() = withContext(Dispatchers.IO) { dao.unfinished().mapNotNull { AgentTaskCodec.decode(it.snapshotJson) } }
}

/**
 * The Agent's Android side: model priority list and routing, the tool adapters over existing app
 * features, task checkpoints and the state the Agent screen shows.
 *
 * The tools reuse existing implementations only: Library documents (Room), the document extraction
 * engine (PDF text / PaddleOCR on ONNX Runtime) through [extractPdf], the Central AI Orchestrator
 * (chat, Smart Templates, Word/Excel/PowerPoint/PDF engines) through [generate], and the share
 * sheet through [share]. Nothing here talks to a provider or a file format directly.
 */
class AgentService(
    private val database: SmartBubbleDatabase,
    private val preferences: PreferencesManager,
    private val providerManager: ProviderManager,
    /** Existing extraction pipeline for a Library PDF without stored text; returns plain text. */
    private val extractPdf: suspend (File) -> String,
    /** Existing orchestrator turn: the request, the source documents as context, the output format. */
    private val generate: suspend (task: AgentTask, userMessage: String, sources: List<ContextMessage>, format: String?) -> GeneratedContent,
    /** Opens the share sheet for a Library document (main thread). */
    private val share: suspend (DocumentEntity) -> Unit
) {
    val store: AgentTaskStore = RoomAgentTaskStore(database.agentTaskDao())

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    /** The priority list as Settings shows it. */
    val priorityEntries: Flow<List<PriorityEntry>> = combine(
        providerManager.providers, preferences.agentProviderOrder, preferences.agentDisabledProviders
    ) { providers, order, disabled -> PriorityList.arrange(providers, order, disabled, providerManager::hasKey) }

    val maxCycles: Flow<Int> get() = preferences.agentMaxCycles

    // ------------------------------------------------------------------ routing

    private fun supportOf(p: ProviderEntity): ModelSupport {
        val info = ModelCatalogCache.get(p.baseUrl, p.modelName) ?: return ModelSupport.UNKNOWN
        return ModelSupport(
            structuredOutput = info.capabilities.jsonOutput,
            imageInput = info.capabilities.vision,
            toolCalling = info.capabilities.toolCalling,
            contextTokens = info.contextLength
        )
    }

    /** A routing session over the user's current priority list. */
    suspend fun newRoutingSession(): Pair<RoutingSession, ProviderEntity> {
        val providers = withContext(Dispatchers.IO) { providerManager.providers.first() }
        if (providers.isEmpty()) throw AiException(AiErrorKind.NO_DEFAULT_PROVIDER)
        val entries = PriorityList.arrange(providers, preferences.agentProviderOrder.first(), preferences.agentDisabledProviders.first(), providerManager::hasKey)
        val candidates = PriorityList.candidates(entries, ::supportOf)
        if (candidates.isEmpty()) {
            // Every model is switched off or has no key: say which, as before.
            throw AiException(if (entries.none { it.hasKey }) AiErrorKind.MISSING_API_KEY else AiErrorKind.NO_COMPATIBLE_MODEL)
        }
        val byId = providers.associateBy { it.id }
        val policy = RoutingPolicy(maxCycles = preferences.agentMaxCycles.first())
        _state.update { it.copy(routing = RoutingStatus(candidates.first().name, candidates.first().model)) }
        val session = RoutingSession(
            candidates = candidates,
            policy = policy,
            clientFor = { c -> providerManager.clientFor(byId.getValue(c.id)) },
            onEvent = ::onRoutingEvent
        )
        return session to byId.getValue(candidates.first().id)
    }

    /** Client for one chat turn: the orchestrator uses it like the default provider's client. */
    suspend fun turnClient(onRestart: () -> Unit): Pair<ProviderEntity, RoutedAiClient> {
        val (session, first) = newRoutingSession()
        currentSession = session
        return first to RoutedAiClient(session, onRestart)
    }

    /** The routing session of the request running now (to read who answered and every attempt). */
    @Volatile
    var currentSession: RoutingSession? = null
        private set

    private fun onRoutingEvent(event: RoutingEvent) {
        _state.update { s ->
            val r = s.routing ?: RoutingStatus()
            // The list keeps what the user needs to see: failures, skipped models and pauses.
            fun withEvent() = (r.events + event).takeLast(RoutingStatus.MAX_EVENTS)
            val routing = when (event) {
                is RoutingEvent.Attempt -> r.copy(providerName = event.candidate.name, model = event.candidate.model, cycle = event.cycle, waitingMs = null)
                is RoutingEvent.Served -> r.copy(providerName = event.candidate.name, model = event.candidate.model, waitingMs = null)
                is RoutingEvent.Waiting -> r.copy(waitingMs = event.delayMs, cycle = event.nextCycle, events = withEvent())
                is RoutingEvent.Failed, is RoutingEvent.Skipped -> r.copy(events = withEvent())
            }
            s.copy(routing = routing)
        }
    }

    /** Clears the routing line once nothing is running. */
    fun clearRouting() {
        currentSession = null
        _state.update { it.copy(routing = null) }
    }

    // ------------------------------------------------------------------ settings

    suspend fun moveProvider(from: Int, to: Int) {
        val ids = priorityEntries.first().map { it.provider.id }
        preferences.setAgentProviderOrder(PriorityList.move(ids, from, to))
    }

    suspend fun setProviderEnabled(id: String, enabled: Boolean) = preferences.setAgentProviderEnabled(id, enabled)
    suspend fun setMaxCycles(cycles: Int) = preferences.setAgentMaxCycles(cycles)

    // ------------------------------------------------------------------ tasks

    suspend fun analyze(text: String): AgentIntent {
        val library = withContext(Dispatchers.IO) { database.documentDao().getAllDocuments().first() }
            .map { LibraryRef(it.id, it.title, it.fileType) }
        return IntentAnalyzer.analyze(text, library)
    }

    val registry: ToolRegistry = ToolRegistry(
        granted = setOf(ToolPermission.READ_LIBRARY, ToolPermission.NETWORK_AI, ToolPermission.WRITE_LIBRARY, ToolPermission.SHARE_EXTERNALLY)
    ).register(LibraryReadTool()).register(ContentGenerateTool()).register(ResultValidateTool()).register(DocumentShareTool())

    val engine = AgentEngine(registry, store, onUpdate = { t -> _state.update { s -> if (s.task == null || s.task.id == t.id) s.copy(task = t) else s } })

    fun show(task: AgentTask?) = _state.update { it.copy(task = task) }
    fun requestPause(taskId: String) = engine.requestPause(taskId)

    /** The latest unfinished or just-finished task of [sessionId], for the Agent screen. */
    suspend fun latestFor(sessionId: String): AgentTask? = store.forSession(sessionId).maxByOrNull { it.createdAt }

    /** After an app restart: interrupted tasks become PAUSED (resumable), checking their side effects. */
    suspend fun recoverInterrupted(): List<AgentTask> = store.unfinished().map { engine.recover(it) }

    private inner class LibraryReadTool : AgentTool {
        override val spec = ToolSpec(
            id = TaskPlanner.Tools.LIBRARY_READ, name = "Read Library document",
            description = "Reads the text of a Library document; extracts a PDF without stored text with the existing extraction engine.",
            requiredInputs = setOf("documentId"), outputs = setOf("title", "fileType", "text", "figures"),
            permissions = setOf(ToolPermission.READ_LIBRARY)
        )

        override suspend fun execute(call: ToolCall): Map<String, String> = withContext(Dispatchers.IO) {
            val doc = database.documentDao().getDocumentById(call.step.input.getValue("documentId"))
                ?: throw ToolException("document_not_found")
            var text = doc.textContent
            if (text.isBlank() && doc.fileType == "PDF") {
                val file = File(doc.filePath)
                if (!file.isFile) throw ToolException("file_not_found")
                text = extractPdf(file)
            }
            if (text.isBlank()) throw ToolException("document_empty")
            val figures = TableStats.describe(TableStats.columns(text))
            mapOf(
                "title" to doc.title, "fileType" to doc.fileType,
                "text" to text.take(MAX_SOURCE_CHARS), "figures" to figures
            )
        }
    }

    private inner class ContentGenerateTool : AgentTool {
        override val spec = ToolSpec(
            id = TaskPlanner.Tools.CONTENT_GENERATE, name = "Write content",
            description = "Runs the existing orchestrator (routed models, Smart Templates, document engines) on the request and the source texts.",
            requiredInputs = setOf("request"), optionalInputs = setOf("format"),
            outputs = setOf("reply", "documentId", "templateApplied", "templateRequested"),
            permissions = setOf(ToolPermission.NETWORK_AI, ToolPermission.WRITE_LIBRARY), sideEffect = true
        )

        private fun userMessage(call: ToolCall): String {
            val figures = call.dependencies.values.mapNotNull { out ->
                out["figures"]?.takeIf { it.isNotBlank() }?.let { "${out["title"]}:\n$it" }
            }
            val request = call.step.input.getValue("request")
            return if (figures.isEmpty()) request
            else "$request\n\n[Figures computed by the app from the source tables; use these exact values]\n" + figures.joinToString("\n")
        }

        override suspend fun execute(call: ToolCall): Map<String, String> {
            val sources = call.step.dependsOn.mapNotNull { id ->
                val out = call.dependencies[id] ?: return@mapNotNull null
                ContextMessage(isUser = true, text = "Source document: ${out["title"]}", attachedDocumentText = out["text"], attachedDocumentTitle = out["title"])
            }
            val result = generate(call.task, userMessage(call), sources, call.step.input["format"])
            return buildMap {
                put("reply", result.reply.take(MAX_SOURCE_CHARS))
                result.document?.let { put("documentId", it.id) }
                put("templateApplied", result.templateApplied.toString())
                put("templateRequested", result.templateRequested.toString())
            }
        }

        /** A document this run saved before the app stopped: same request, created after the run started. */
        override suspend fun reconcile(call: ToolCall): Map<String, String>? = withContext(Dispatchers.IO) {
            val started = call.step.startedAt ?: return@withContext null
            val message = userMessage(call)
            val doc = database.documentDao().getAllDocuments().first()
                .filter { it.sourceRequest == message && it.createdAt >= started }
                .maxByOrNull { it.createdAt } ?: return@withContext null
            mapOf("reply" to "", "documentId" to doc.id, "templateApplied" to (doc.templateId != null).toString(), "templateRequested" to "false")
        }
    }

    private inner class ResultValidateTool : AgentTool {
        override val spec = ToolSpec(
            id = TaskPlanner.Tools.RESULT_VALIDATE, name = "Check result",
            description = "Checks that the requested output exists, has the right format and is not empty.",
            optionalInputs = setOf("format"), outputs = setOf("outcome", "issues", "documentId"),
            permissions = setOf(ToolPermission.READ_LIBRARY)
        )

        override suspend fun execute(call: ToolCall): Map<String, String> = withContext(Dispatchers.IO) {
            val generated = call.dependencies[call.step.dependsOn.first()].orEmpty()
            val doc = generated["documentId"]?.let { database.documentDao().getDocumentById(it) }
            val produced = doc?.let {
                val file = File(it.filePath)
                ProducedDocument(it.fileType, file.isFile, if (file.isFile) file.length() else 0L, it.textContent.length, it.templateId != null || generated["templateApplied"] == "true")
            }
            val report = ResultValidator.validate(
                expectedFormat = call.step.input["format"],
                reply = generated["reply"].orEmpty(),
                document = produced,
                documentText = doc?.textContent.orEmpty(),
                templateRequested = generated["templateRequested"] == "true"
            )
            buildMap {
                put("outcome", report.outcome.name)
                put("issues", report.issues.joinToString(",") { it.name })
                doc?.let { put("documentId", it.id) }
            }
        }
    }

    private inner class DocumentShareTool : AgentTool {
        override val spec = ToolSpec(
            id = TaskPlanner.Tools.DOCUMENT_SHARE, name = "Share document",
            description = "Opens the Android share sheet for the created document (the user picks the app).",
            outputs = setOf("shared"), permissions = setOf(ToolPermission.SHARE_EXTERNALLY),
            sideEffect = true, needsApproval = true
        )

        override suspend fun execute(call: ToolCall): Map<String, String> {
            val id = call.dependencies.values.firstNotNullOfOrNull { it["documentId"] } ?: throw ToolException("no_document")
            val doc = withContext(Dispatchers.IO) { database.documentDao().getDocumentById(id) } ?: throw ToolException("document_not_found")
            withContext(Dispatchers.Main) { share(doc) }
            return mapOf("shared" to id)
        }
    }

    companion object {
        const val MAX_SOURCE_CHARS = 12_000

        fun newId(): String = UUID.randomUUID().toString()
    }
}
