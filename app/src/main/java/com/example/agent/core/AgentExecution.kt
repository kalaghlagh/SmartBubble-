package com.example.agent.core

import com.example.ai.providers.AiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------- tools

/** What a tool may touch. A tool is only run when every permission it needs is granted. */
enum class ToolPermission { READ_LIBRARY, NETWORK_AI, WRITE_LIBRARY, SHARE_EXTERNALLY }

/** Stable contract of a registered tool. */
data class ToolSpec(
    val id: String,
    val name: String,
    val description: String,
    val requiredInputs: Set<String> = emptySet(),
    val optionalInputs: Set<String> = emptySet(),
    val outputs: Set<String> = emptySet(),
    val permissions: Set<ToolPermission> = emptySet(),
    /** Changes something outside the task (a file, the Library, another app). */
    val sideEffect: Boolean = false,
    /** The user approves each run first (sending content outside the app, overwriting, deleting). */
    val needsApproval: Boolean = false
)

/** Inputs of one tool run plus the outputs of the steps it depends on. */
data class ToolCall(val task: AgentTask, val step: AgentStep, val dependencies: Map<String, Map<String, String>>)

/** A tool failure that is not an AI provider error. [code] is stable and shown localized. */
class ToolException(val code: String, message: String? = null) : Exception(message ?: code)

interface AgentTool {
    val spec: ToolSpec

    suspend fun execute(call: ToolCall): Map<String, String>

    /**
     * After the app stopped during a run of this tool: the result of that run if it can be found
     * (e.g. the document it saved), else null. Only side-effect tools need it.
     */
    suspend fun reconcile(call: ToolCall): Map<String, String>? = null
}

/**
 * The strict allow-list of tools the Agent can call. Only registered tools run, only with
 * validated inputs and granted permissions. Model output never names or calls a tool: steps
 * come from [TaskPlanner].
 */
class ToolRegistry(private val granted: Set<ToolPermission>) {
    private val tools = LinkedHashMap<String, AgentTool>()

    fun register(tool: AgentTool): ToolRegistry {
        require(tool.spec.id !in tools) { "Tool ${tool.spec.id} is already registered" }
        tools[tool.spec.id] = tool
        return this
    }

    operator fun get(id: String): AgentTool? = tools[id]
    val specs: List<ToolSpec> get() = tools.values.map { it.spec }

    /** Null when [step] may run now, else the reason (a stable code). */
    fun check(step: AgentStep): String? {
        val tool = tools[step.toolId] ?: return "unknown_tool"
        val spec = tool.spec
        if (!step.input.keys.containsAll(spec.requiredInputs)) return "missing_input"
        if (step.input.keys.any { it !in spec.requiredInputs && it !in spec.optionalInputs }) return "unexpected_input"
        if (step.input.values.any { it.length > MAX_INPUT_CHARS }) return "input_too_large"
        if (!granted.containsAll(spec.permissions)) return "permission_denied"
        if (spec.needsApproval && step.approval != ApprovalState.APPROVED) return "approval_required"
        return null
    }

    companion object {
        const val MAX_INPUT_CHARS = 20_000
    }
}

// ---------------------------------------------------------------------------- validation

enum class ValidationOutcome { COMPLETED, COMPLETED_WITH_WARNINGS, INCOMPLETE, FAILED }

enum class ValidationIssue { NO_OUTPUT, EMPTY_REPLY, FILE_MISSING, FILE_EMPTY, WRONG_FORMAT, EMPTY_CONTENT, TEMPLATE_NOT_APPLIED, MISSING_SECTIONS, SOURCES_UNREAD }

/** What the generate step produced, as checked on disk and in the Library. */
data class ProducedDocument(
    val fileType: String,
    val fileExists: Boolean,
    val fileSize: Long,
    val textLength: Int,
    val templateApplied: Boolean
)

data class ValidationReport(val outcome: ValidationOutcome, val issues: List<ValidationIssue>)

/**
 * Structural checks of a result. Passing them means the requested output exists in the right
 * format and is not empty; it does NOT mean AI-written content is factually verified.
 */
object ResultValidator {
    const val MIN_TEXT = 20

    fun validate(
        expectedFormat: String?,
        reply: String,
        document: ProducedDocument?,
        documentText: String = "",
        templateRequested: Boolean = false,
        requiredSections: List<String> = emptyList(),
        unreadSources: Int = 0
    ): ValidationReport {
        val failed = ArrayList<ValidationIssue>()
        val incomplete = ArrayList<ValidationIssue>()
        val warnings = ArrayList<ValidationIssue>()
        if (document == null) {
            if (expectedFormat != null) failed += ValidationIssue.NO_OUTPUT
            else if (reply.isBlank()) failed += ValidationIssue.EMPTY_REPLY
        } else {
            if (!document.fileExists) failed += ValidationIssue.FILE_MISSING
            else if (document.fileSize <= 0) failed += ValidationIssue.FILE_EMPTY
            if (expectedFormat != null && !document.fileType.equals(expectedFormat, ignoreCase = true)) incomplete += ValidationIssue.WRONG_FORMAT
            if (document.textLength < MIN_TEXT) incomplete += ValidationIssue.EMPTY_CONTENT
            if (templateRequested && !document.templateApplied) warnings += ValidationIssue.TEMPLATE_NOT_APPLIED
            val lower = documentText.lowercase()
            if (requiredSections.any { it.isNotBlank() && !lower.contains(it.lowercase()) }) warnings += ValidationIssue.MISSING_SECTIONS
        }
        if (unreadSources > 0) incomplete += ValidationIssue.SOURCES_UNREAD
        val outcome = when {
            failed.isNotEmpty() -> ValidationOutcome.FAILED
            incomplete.isNotEmpty() -> ValidationOutcome.INCOMPLETE
            warnings.isNotEmpty() -> ValidationOutcome.COMPLETED_WITH_WARNINGS
            else -> ValidationOutcome.COMPLETED
        }
        return ValidationReport(outcome, failed + incomplete + warnings)
    }
}

// ---------------------------------------------------------------------------- persistence

/** Task checkpoints. Implementations must be safe to call from any thread. */
interface AgentTaskStore {
    suspend fun save(task: AgentTask)
    suspend fun load(id: String): AgentTask?
    suspend fun forSession(sessionId: String): List<AgentTask>
    suspend fun unfinished(): List<AgentTask>
}

class InMemoryAgentTaskStore : AgentTaskStore {
    private val tasks = LinkedHashMap<String, AgentTask>()
    override suspend fun save(task: AgentTask) = synchronized(tasks) { tasks[task.id] = task }
    override suspend fun load(id: String) = synchronized(tasks) { tasks[id] }
    override suspend fun forSession(sessionId: String) = synchronized(tasks) { tasks.values.filter { it.sessionId == sessionId } }
    override suspend fun unfinished() = synchronized(tasks) { tasks.values.filter { !it.status.isFinal } }
}

// ---------------------------------------------------------------------------- execution

/**
 * Runs a plan step by step in dependency order, through [registry] only. Every state change is
 * checkpointed in [store] before and after each tool run, so a task can be paused, resumed after
 * an app restart, or cancelled, and a completed step is never run again.
 *
 * - Pause: takes effect between steps (a running step finishes first); the task is saved PAUSED.
 * - Cancel: cancel the coroutine running [run]; then call [cancelled] to record it.
 * - Approval: a step that needs it stops the task in WAITING_FOR_APPROVAL; [decide] records the
 *   user's answer and [run] continues.
 * - Restart: [recover] turns an interrupted task into PAUSED; a side-effect step whose result
 *   cannot be confirmed becomes UNKNOWN and needs approval before it runs again.
 */
class AgentEngine(
    private val registry: ToolRegistry,
    private val store: AgentTaskStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onUpdate: (AgentTask) -> Unit = {}
) {
    @Volatile
    private var pauseRequested: String? = null

    /** Asks task [taskId] to pause after its current step. */
    fun requestPause(taskId: String) {
        pauseRequested = taskId
    }

    private suspend fun checkpoint(task: AgentTask): AgentTask {
        val t = task.copy(updatedAt = clock())
        withContext(NonCancellable) { store.save(t) }
        onUpdate(t)
        return t
    }

    suspend fun run(start: AgentTask): AgentTask {
        if (pauseRequested == start.id) pauseRequested = null
        TaskPlanner.executionOrder(start.steps) // rejects an invalid plan before anything runs
        var task = checkpoint(start.copy(status = TaskStatus.RUNNING, error = null))
        while (true) {
            currentCoroutineContext().ensureActive()
            if (pauseRequested == task.id) {
                pauseRequested = null
                return checkpoint(task.copy(status = TaskStatus.PAUSED, currentStepId = null))
            }
            val next = task.steps.firstOrNull { s ->
                (s.status == StepStatus.PENDING || s.status == StepStatus.UNKNOWN || s.status == StepStatus.WAITING_FOR_APPROVAL) &&
                    s.dependsOn.all { d -> task.step(d)?.status?.satisfied == true }
            } ?: return checkpoint(finish(task))

            val tool = registry[next.toolId]
            val needsApproval = (tool?.spec?.needsApproval == true || next.status == StepStatus.UNKNOWN) &&
                next.approval != ApprovalState.APPROVED
            if (next.approval == ApprovalState.REJECTED) {
                task = checkpoint(task.withStep(next.copy(status = StepStatus.REJECTED, finishedAt = clock())))
                continue
            }
            if (needsApproval) {
                val waiting = next.copy(status = StepStatus.WAITING_FOR_APPROVAL, approval = ApprovalState.PENDING)
                return checkpoint(task.withStep(waiting).copy(status = TaskStatus.WAITING_FOR_APPROVAL, currentStepId = next.id))
            }
            val problem = registry.check(next)
            if (problem != null) {
                val failed = next.copy(status = StepStatus.FAILED, error = problem, finishedAt = clock())
                return checkpoint(task.withStep(failed).copy(status = TaskStatus.FAILED, error = problem, currentStepId = null))
            }

            val running = next.copy(status = StepStatus.RUNNING, startedAt = clock(), error = null)
            task = checkpoint(task.withStep(running).copy(currentStepId = running.id))
            val call = ToolCall(task, running, running.dependsOn.associateWith { task.step(it)?.output.orEmpty() })
            try {
                val output = tool!!.execute(call)
                task = checkpoint(task.withStep(running.copy(status = StepStatus.COMPLETED, output = output, finishedAt = clock())))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                return checkpoint(fail(task, running, "ai:${e.kind.name}"))
            } catch (e: ToolException) {
                return checkpoint(fail(task, running, e.code))
            } catch (e: Exception) {
                return checkpoint(fail(task, running, "error:${e.javaClass.simpleName}"))
            }
        }
    }

    private fun fail(task: AgentTask, step: AgentStep, code: String) =
        task.withStep(step.copy(status = StepStatus.FAILED, error = code, finishedAt = clock()))
            .copy(status = TaskStatus.FAILED, error = code, currentStepId = null)

    /** Final status once no step can run: from the validation step and any rejected or blocked step. */
    private fun finish(task: AgentTask): AgentTask {
        val blocked = task.steps.filter { it.status == StepStatus.PENDING || it.status == StepStatus.UNKNOWN }
        val rejected = task.steps.filter { it.status == StepStatus.REJECTED }
        val validation = task.steps.firstOrNull { it.toolId == TaskPlanner.Tools.RESULT_VALIDATE }
        val outcome = validation?.output?.get("outcome")?.let { runCatching { ValidationOutcome.valueOf(it) }.getOrNull() }
        val warnings = task.warnings + rejected.map { "rejected:${it.id}" } +
            validation?.output?.get("issues").orEmpty().split(',').filter { it.isNotBlank() }.map { "issue:$it" }
        val status = when {
            task.steps.any { it.status == StepStatus.FAILED } -> TaskStatus.FAILED
            outcome == ValidationOutcome.FAILED -> TaskStatus.FAILED
            outcome == ValidationOutcome.INCOMPLETE || blocked.isNotEmpty() -> TaskStatus.INCOMPLETE
            outcome == ValidationOutcome.COMPLETED_WITH_WARNINGS || rejected.isNotEmpty() -> TaskStatus.COMPLETED_WITH_WARNINGS
            else -> TaskStatus.COMPLETED
        }
        return task.copy(status = status, warnings = warnings.distinct(), currentStepId = null)
    }

    /** Records the user's answer for the step waiting for approval. Call [run] afterwards. */
    suspend fun decide(task: AgentTask, stepId: String, approved: Boolean): AgentTask {
        val step = task.step(stepId) ?: return task
        if (step.status != StepStatus.WAITING_FOR_APPROVAL) return task
        val updated = step.copy(
            approval = if (approved) ApprovalState.APPROVED else ApprovalState.REJECTED,
            status = StepStatus.PENDING
        )
        return checkpoint(task.withStep(updated).copy(status = TaskStatus.PAUSED))
    }

    /** Records a cancellation (call after cancelling the coroutine running [run]). */
    suspend fun cancelled(task: AgentTask): AgentTask {
        val steps = task.steps.map {
            when (it.status) {
                StepStatus.PENDING, StepStatus.WAITING_FOR_APPROVAL, StepStatus.UNKNOWN -> it.copy(status = StepStatus.CANCELLED)
                // The tool was stopped mid-run: the orchestrator keeps nothing it did not finish.
                StepStatus.RUNNING -> it.copy(status = StepStatus.CANCELLED, finishedAt = clock())
                else -> it
            }
        }
        return checkpoint(task.copy(steps = steps, status = TaskStatus.CANCELLED, currentStepId = null))
    }

    /** After an app restart: an interrupted task becomes PAUSED, checking side effects it may have made. */
    suspend fun recover(task: AgentTask): AgentTask {
        if (task.status.isFinal || task.status == TaskStatus.PAUSED || task.status == TaskStatus.WAITING_FOR_APPROVAL) return task
        var t = task
        for (step in task.steps.filter { it.status == StepStatus.RUNNING }) {
            val tool = registry[step.toolId]
            val replaced = if (tool?.spec?.sideEffect == true) {
                val found = try {
                    tool.reconcile(ToolCall(t, step, step.dependsOn.associateWith { t.step(it)?.output.orEmpty() }))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (found != null) step.copy(status = StepStatus.COMPLETED, output = found, finishedAt = clock())
                else step.copy(status = StepStatus.UNKNOWN, approval = ApprovalState.PENDING, error = "completion_unknown")
            } else step.copy(status = StepStatus.PENDING)
            t = t.withStep(replaced)
        }
        return checkpoint(t.copy(status = TaskStatus.PAUSED, currentStepId = null))
    }
}
