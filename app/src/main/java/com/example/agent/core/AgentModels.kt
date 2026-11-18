package com.example.agent.core

import com.example.agent.routing.FailoverDecision
import com.example.agent.routing.ProviderAttempt
import com.example.ai.providers.AiErrorKind
import com.example.ai.providers.Json

/** Quick Mode: a simple request answered at once. Agent Mode: a planned, multi-step task. */
enum class AgentMode { QUICK, AGENT }

enum class TaskStatus {
    PENDING, PLANNING, RUNNING, WAITING_FOR_APPROVAL, PAUSED,
    COMPLETED, COMPLETED_WITH_WARNINGS, INCOMPLETE, FAILED, CANCELLED;

    val isFinal: Boolean get() = this in setOf(COMPLETED, COMPLETED_WITH_WARNINGS, INCOMPLETE, FAILED, CANCELLED)
}

enum class StepStatus {
    PENDING, RUNNING, WAITING_FOR_APPROVAL, COMPLETED, FAILED, CANCELLED, REJECTED,

    /** The app stopped while this step was running and its result could not be confirmed. */
    UNKNOWN;

    /** Finished in a way that lets dependent steps run. */
    val satisfied: Boolean get() = this == COMPLETED
}

enum class ApprovalState { NOT_REQUIRED, PENDING, APPROVED, REJECTED }

/** Where a file the Agent may use lives. */
enum class FileSource { LIBRARY, MY_TEMPLATE }

/** What a task does with a file: read it for information, use it for formatting, or edit it (as a copy). */
enum class FileRole { SOURCE, TEMPLATE, EDIT_TARGET }

/**
 * A file a task may use. [id] is the app-managed id ("library:<document id>" or
 * "template:<template id>"); tools take only such ids, never paths. [granted] false: the user
 * revoked access, and every tool refuses the file from then on.
 */
data class TaskFile(
    val id: String,
    val source: FileSource,
    val name: String,
    val fileType: String,
    val role: FileRole,
    val granted: Boolean = true
) {
    companion object {
        fun libraryId(documentId: String) = "library:$documentId"
        fun templateId(templateId: String) = "template:$templateId"
    }
}

/**
 * One step of a plan. [executionId] identifies this execution of the tool: it is persisted with
 * the step before the tool starts, so after a restart the app knows a side effect may have
 * happened and checks for it instead of repeating it blindly.
 */
data class AgentStep(
    val id: String,
    val toolId: String,
    val input: Map<String, String> = emptyMap(),
    val dependsOn: List<String> = emptyList(),
    val approval: ApprovalState = ApprovalState.NOT_REQUIRED,
    val status: StepStatus = StepStatus.PENDING,
    val output: Map<String, String> = emptyMap(),
    val error: String? = null,
    val executionId: String,
    val startedAt: Long? = null,
    val finishedAt: Long? = null
)

/** An Agent task: the request, its plan, progress, results and checkpoints. */
data class AgentTask(
    val id: String,
    val sessionId: String,
    val request: String,
    val createdAt: Long,
    val mode: AgentMode,
    val status: TaskStatus,
    val steps: List<AgentStep>,
    val outputFormat: String? = null,
    val language: String = "",
    val currentStepId: String? = null,
    val error: String? = null,
    val warnings: List<String> = emptyList(),
    val attempts: List<ProviderAttempt> = emptyList(),
    /** Files this task may use, with their role and whether access is still granted. */
    val files: List<TaskFile> = emptyList(),
    val updatedAt: Long = createdAt
) {
    fun file(id: String): TaskFile? = files.firstOrNull { it.id == id }
    fun isGranted(id: String): Boolean = file(id)?.granted == true
    fun step(id: String): AgentStep? = steps.firstOrNull { it.id == id }
    fun withStep(step: AgentStep): AgentTask = copy(steps = steps.map { if (it.id == step.id) step else it })
    val completedSteps: Int get() = steps.count { it.status == StepStatus.COMPLETED }
    val pendingApproval: AgentStep? get() = steps.firstOrNull { it.status == StepStatus.WAITING_FOR_APPROVAL }
}

/**
 * JSON form of a task for the agent_tasks table. Only what is needed to show and resume a task:
 * no credentials, no provider responses beyond the step outputs the plan itself produced.
 */
object AgentTaskCodec {
    fun encode(task: AgentTask): String = Json.write(
        mapOf(
            "v" to 1,
            "id" to task.id,
            "sessionId" to task.sessionId,
            "request" to task.request,
            "createdAt" to task.createdAt.toString(),
            "updatedAt" to task.updatedAt.toString(),
            "mode" to task.mode.name,
            "status" to task.status.name,
            "outputFormat" to task.outputFormat,
            "language" to task.language,
            "currentStepId" to task.currentStepId,
            "error" to task.error,
            "warnings" to task.warnings,
            "files" to task.files.map {
                mapOf("id" to it.id, "source" to it.source.name, "name" to it.name, "fileType" to it.fileType, "role" to it.role.name, "granted" to it.granted)
            },
            "attempts" to task.attempts.map {
                mapOf(
                    "providerId" to it.providerId, "providerName" to it.providerName, "model" to it.model,
                    "cycle" to it.cycle, "error" to it.error?.name, "decision" to it.decision?.name
                )
            },
            "steps" to task.steps.map { s ->
                mapOf(
                    "id" to s.id, "toolId" to s.toolId, "input" to s.input, "dependsOn" to s.dependsOn,
                    "approval" to s.approval.name, "status" to s.status.name, "output" to s.output,
                    "error" to s.error, "executionId" to s.executionId,
                    "startedAt" to s.startedAt?.toString(), "finishedAt" to s.finishedAt?.toString()
                )
            }
        )
    )

    /** Null when [text] is not a task this version can read (the row is then ignored, never guessed). */
    fun decode(text: String): AgentTask? = try {
        val root = Json.obj(Json.parse(text)) ?: return null
        AgentTask(
            id = root["id"] as String,
            sessionId = root["sessionId"] as String,
            request = root["request"] as String,
            createdAt = (root["createdAt"] as String).toLong(),
            updatedAt = (root["updatedAt"] as? String)?.toLongOrNull() ?: (root["createdAt"] as String).toLong(),
            mode = AgentMode.valueOf(root["mode"] as String),
            status = TaskStatus.valueOf(root["status"] as String),
            outputFormat = root["outputFormat"] as? String,
            language = root["language"] as? String ?: "",
            currentStepId = root["currentStepId"] as? String,
            error = root["error"] as? String,
            warnings = Json.arr(root["warnings"])?.mapNotNull { it as? String }.orEmpty(),
            files = Json.arr(root["files"]).orEmpty().mapNotNull { f ->
                val m = Json.obj(f) ?: return@mapNotNull null
                TaskFile(
                    id = m["id"] as String,
                    source = FileSource.valueOf(m["source"] as String),
                    name = m["name"] as? String ?: "",
                    fileType = m["fileType"] as? String ?: "",
                    role = FileRole.valueOf(m["role"] as String),
                    granted = m["granted"] as? Boolean ?: false
                )
            },
            attempts = Json.arr(root["attempts"]).orEmpty().mapNotNull { a ->
                val m = Json.obj(a) ?: return@mapNotNull null
                ProviderAttempt(
                    providerId = m["providerId"] as String,
                    providerName = m["providerName"] as? String ?: "",
                    model = m["model"] as? String ?: "",
                    cycle = (m["cycle"] as? Number)?.toInt() ?: 1,
                    error = (m["error"] as? String)?.let { e -> runCatching { AiErrorKind.valueOf(e) }.getOrNull() },
                    decision = (m["decision"] as? String)?.let { d -> runCatching { FailoverDecision.valueOf(d) }.getOrNull() }
                )
            },
            steps = Json.arr(root["steps"]).orEmpty().map { raw ->
                val m = Json.obj(raw)!!
                AgentStep(
                    id = m["id"] as String,
                    toolId = m["toolId"] as String,
                    input = strings(m["input"]),
                    dependsOn = Json.arr(m["dependsOn"])?.mapNotNull { it as? String }.orEmpty(),
                    approval = ApprovalState.valueOf(m["approval"] as String),
                    status = StepStatus.valueOf(m["status"] as String),
                    output = strings(m["output"]),
                    error = m["error"] as? String,
                    executionId = m["executionId"] as String,
                    startedAt = (m["startedAt"] as? String)?.toLongOrNull(),
                    finishedAt = (m["finishedAt"] as? String)?.toLongOrNull()
                )
            }
        )
    } catch (e: Exception) {
        null
    }

    private fun strings(value: Any?): Map<String, String> =
        Json.obj(value)?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }?.toMap().orEmpty()
}
