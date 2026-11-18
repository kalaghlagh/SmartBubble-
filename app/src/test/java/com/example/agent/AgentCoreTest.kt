package com.example.agent

import com.example.agent.core.AgentEngine
import com.example.agent.core.AgentIntent
import com.example.agent.core.AgentMode
import com.example.agent.core.AgentStep
import com.example.agent.core.AgentTaskCodec
import com.example.agent.core.AgentTool
import com.example.agent.core.ApprovalState
import com.example.agent.core.InMemoryAgentTaskStore
import com.example.agent.core.IntentAnalyzer
import com.example.agent.core.LibraryRef
import com.example.agent.core.ProducedDocument
import com.example.agent.core.ResultValidator
import com.example.agent.core.StepStatus
import com.example.agent.core.TableStats
import com.example.agent.core.TaskPlanner
import com.example.agent.core.TaskStatus
import com.example.agent.core.ToolCall
import com.example.agent.core.ToolException
import com.example.agent.core.ToolPermission
import com.example.agent.core.ToolRegistry
import com.example.agent.core.ToolSpec
import com.example.agent.core.ValidationIssue
import com.example.agent.core.ValidationOutcome
import com.example.agent.routing.FailoverDecision
import com.example.agent.routing.ProviderAttempt
import com.example.ai.providers.AiErrorKind
import com.example.ai.providers.AiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Agent Core: intent, planning, tool registry, execution engine, checkpoints, validation. */
class AgentCoreTest {

    private val library = listOf(
        LibraryRef("d1", "Sales 2025.xlsx", "XLSX"),
        LibraryRef("d2", "تقرير المبيعات.docx", "DOCX"),
        LibraryRef("d3", "Rapport annuel.pdf", "PDF")
    )

    // ------------------------------------------------------------------ intent

    @Test
    fun simpleRequests_areQuickMode() {
        listOf(
            "Write a letter to my landlord",
            "اكتب رسالة شكر لزميلي",
            "Traduis ce texte en anglais",
            "Create a simple table of my weekly tasks",
            "Write a word document about climate change"
        ).forEach { assertEquals(it, AgentMode.QUICK, IntentAnalyzer.analyze(it, library).mode) }
    }

    @Test
    fun requestsNamingLibraryDocuments_areAgentMode_inThreeLanguages() {
        val en = IntentAnalyzer.analyze("Analyze Sales 2025 and create a Word report", library)
        assertEquals(AgentMode.AGENT, en.mode)
        assertEquals("DOCX", en.outputFormat)
        assertEquals(listOf("d1"), en.sources.map { it.id })

        val ar = IntentAnalyzer.analyze("لخص تقرير المبيعات في عرض تقديمي", library)
        assertEquals(AgentMode.AGENT, ar.mode)
        assertEquals("PPTX", ar.outputFormat)
        assertEquals(listOf("d2"), ar.sources.map { it.id })

        val fr = IntentAnalyzer.analyze("Compare Rapport annuel et Sales 2025 dans un tableur", library)
        assertEquals(listOf("d3", "d1"), fr.sources.map { it.id })
        assertEquals("XLSX", fr.outputFormat)
    }

    @Test
    fun shareRequests_needApprovalSteps_onlyWithSomethingToShare() {
        assertEquals(AgentMode.QUICK, IntentAnalyzer.analyze("Send me a joke", library).mode)
        val share = IntentAnalyzer.analyze("Create a PDF summary of Sales 2025 and share it", library)
        assertTrue(share.wantsShare)
        val task = plan(share)
        assertEquals(ApprovalState.PENDING, task.step("share")!!.approval)
    }

    // ------------------------------------------------------------------ planner

    private var ids = 0
    private fun plan(intent: AgentIntent, request: String = "req") =
        TaskPlanner.plan("t1", "s1", request, intent, "en", 1000L) { "x${ids++}" }

    @Test
    fun plan_isBoundedOrderedAndRespectsDependencies() {
        val many = (1..9).map { LibraryRef("id$it", "Document number $it", "DOCX") }
        val intent = IntentAnalyzer.analyze(many.joinToString(" ") { it.title } + " share as pdf", many)
        val task = plan(intent)
        assertTrue(task.steps.size <= TaskPlanner.MAX_STEPS)
        assertEquals(IntentAnalyzer.MAX_SOURCES, task.steps.count { it.toolId == TaskPlanner.Tools.FILE_READ })
        val order = TaskPlanner.executionOrder(task.steps).map { it.id }
        assertTrue(order.indexOf("generate") > order.indexOf("read_5"))
        assertTrue(order.indexOf("validate") > order.indexOf("generate"))
        assertTrue(order.indexOf("share") > order.indexOf("validate"))
        assertEquals(task.steps.size, task.steps.map { it.executionId }.toSet().size)
    }

    @Test
    fun executionOrder_rejectsCyclesAndUnknownDependencies() {
        val a = AgentStep("a", "t", dependsOn = listOf("b"), executionId = "1")
        val b = AgentStep("b", "t", dependsOn = listOf("a"), executionId = "2")
        assertTrue(runCatching { TaskPlanner.executionOrder(listOf(a, b)) }.isFailure)
        assertTrue(runCatching { TaskPlanner.executionOrder(listOf(a)) }.isFailure)
        assertEquals(listOf("b", "a"), TaskPlanner.executionOrder(listOf(a, b.copy(dependsOn = emptyList()))).map { it.id })
    }

    // ------------------------------------------------------------------ registry

    private class FakeTool(
        override val spec: ToolSpec,
        val runs: MutableList<String>,
        val behavior: suspend (ToolCall) -> Map<String, String> = { mapOf("ok" to "1") },
        val reconciled: Map<String, String>? = null
    ) : AgentTool {
        override suspend fun execute(call: ToolCall): Map<String, String> {
            runs += call.step.id
            return behavior(call)
        }

        override suspend fun reconcile(call: ToolCall) = reconciled
    }

    @Test
    fun registry_isAStrictAllowList() {
        val runs = mutableListOf<String>()
        val registry = ToolRegistry(setOf(ToolPermission.READ_LIBRARY))
            .register(FakeTool(ToolSpec("read", "Read", "", requiredInputs = setOf("documentId"), permissions = setOf(ToolPermission.READ_LIBRARY)), runs))
            .register(FakeTool(ToolSpec("share", "Share", "", permissions = setOf(ToolPermission.SHARE_EXTERNALLY), needsApproval = true), runs))
        assertTrue(runCatching { registry.register(FakeTool(ToolSpec("read", "dup", ""), runs)) }.isFailure)
        assertEquals("unknown_tool", registry.check(AgentStep("s", "shell.exec", executionId = "e")))
        assertEquals("missing_input", registry.check(AgentStep("s", "read", executionId = "e")))
        assertEquals("unexpected_input", registry.check(AgentStep("s", "read", mapOf("documentId" to "1", "path" to "/etc"), executionId = "e")))
        assertEquals("input_too_large", registry.check(AgentStep("s", "read", mapOf("documentId" to "x".repeat(20_001)), executionId = "e")))
        assertNull(registry.check(AgentStep("s", "read", mapOf("documentId" to "1"), executionId = "e")))
        assertEquals("permission_denied", registry.check(AgentStep("s", "share", approval = ApprovalState.APPROVED, executionId = "e")))
    }

    // ------------------------------------------------------------------ engine

    private val runs = mutableListOf<String>()
    private val store = InMemoryAgentTaskStore()
    private var generateBehavior: suspend (ToolCall) -> Map<String, String> = { mapOf("reply" to "done", "documentId" to "doc-1") }
    private var reconciled: Map<String, String>? = null

    private fun engine(): AgentEngine {
        val all = setOf(ToolPermission.READ_LIBRARY, ToolPermission.NETWORK_AI, ToolPermission.WRITE_LIBRARY, ToolPermission.SHARE_EXTERNALLY)
        val registry = ToolRegistry(all)
            .register(FakeTool(ToolSpec(TaskPlanner.Tools.FILE_READ, "", "", requiredInputs = setOf("fileId")), runs, { mapOf("title" to "T", "text" to "source") }))
            .register(FakeTool(ToolSpec(TaskPlanner.Tools.CONTENT_GENERATE, "", "", requiredInputs = setOf("request"), optionalInputs = setOf("format"), sideEffect = true), runs, { generateBehavior(it) }, reconciled))
            .register(FakeTool(ToolSpec(TaskPlanner.Tools.RESULT_VALIDATE, "", "", optionalInputs = setOf("format")), runs, { c ->
                mapOf("outcome" to ValidationOutcome.COMPLETED.name, "issues" to "", "documentId" to c.dependencies.values.first()["documentId"].orEmpty())
            }))
            .register(FakeTool(ToolSpec(TaskPlanner.Tools.DOCUMENT_SHARE, "", "", sideEffect = true, needsApproval = true), runs))
        return AgentEngine(registry, store, clock = { 5000L })
    }

    private fun agentTask(share: Boolean = false) =
        plan(IntentAnalyzer.analyze("Report from Sales 2025 as pdf" + if (share) " and share it" else "", library))

    @Test
    fun run_executesStepsInDependencyOrder_passesResults_andCheckpoints() = runBlocking {
        var sourcesSeen: Map<String, Map<String, String>>? = null
        generateBehavior = { c -> sourcesSeen = c.dependencies; mapOf("reply" to "ok", "documentId" to "doc-9") }
        val done = engine().run(agentTask())
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals(listOf("read_1", "generate", "validate"), runs)
        assertEquals("source", sourcesSeen!!["read_1"]!!["text"])
        assertEquals(done, store.load(done.id))
        assertEquals(3, done.completedSteps)
    }

    @Test
    fun approval_stopsTheTask_untilApproved_thenContinues() = runBlocking {
        val e = engine()
        val waiting = e.run(agentTask(share = true))
        assertEquals(TaskStatus.WAITING_FOR_APPROVAL, waiting.status)
        assertEquals(listOf("read_1", "generate", "validate"), runs)
        assertEquals("share", waiting.pendingApproval!!.id)
        val approved = e.decide(waiting, "share", approved = true)
        val done = e.run(approved)
        assertEquals(TaskStatus.COMPLETED, done.status)
        // Completed steps were not run again after the pause for approval.
        assertEquals(listOf("read_1", "generate", "validate", "share"), runs)
    }

    @Test
    fun rejection_skipsTheSensitiveStep_andTheTaskEndsWithWarnings() = runBlocking {
        val e = engine()
        val waiting = e.run(agentTask(share = true))
        val done = e.run(e.decide(waiting, "share", approved = false))
        assertEquals(TaskStatus.COMPLETED_WITH_WARNINGS, done.status)
        assertEquals(StepStatus.REJECTED, done.step("share")!!.status)
        assertTrue("share" !in runs)
    }

    @Test
    fun pause_takesEffectAfterTheCurrentStep_andResumeContinuesFromTheCheckpoint() = runBlocking {
        val e = engine()
        val task = agentTask()
        generateBehavior = { e.requestPause(task.id); mapOf("reply" to "ok", "documentId" to "doc-1") }
        val paused = e.run(task)
        assertEquals(TaskStatus.PAUSED, paused.status)
        assertEquals(StepStatus.COMPLETED, paused.step("generate")!!.status)
        assertEquals(StepStatus.PENDING, paused.step("validate")!!.status)
        val resumed = e.run(store.load(task.id)!!)
        assertEquals(TaskStatus.COMPLETED, resumed.status)
        assertEquals(1, runs.count { it == "generate" })
    }

    @Test
    fun cancel_stopsAtOnce_andIsRecorded() = runBlocking {
        val e = engine()
        val task = agentTask()
        generateBehavior = { awaitCancellation() }
        val job = async { e.run(task) }
        while ("generate" !in runs) yield()
        job.cancel()
        try {
            job.await(); fail()
        } catch (c: CancellationException) {
            // expected
        }
        val cancelled = e.cancelled(store.load(task.id)!!)
        assertEquals(TaskStatus.CANCELLED, cancelled.status)
        assertEquals(StepStatus.CANCELLED, cancelled.step("generate")!!.status)
        assertEquals(StepStatus.CANCELLED, cancelled.step("validate")!!.status)
        assertTrue("validate" !in runs)
    }

    @Test
    fun providerFailure_failsTheTaskWithTheRealError() = runBlocking {
        generateBehavior = { throw AiException(AiErrorKind.QUOTA_OR_BILLING) }
        val failed = engine().run(agentTask())
        assertEquals(TaskStatus.FAILED, failed.status)
        assertEquals("ai:QUOTA_OR_BILLING", failed.error)
        assertTrue("validate" !in runs)
    }

    @Test
    fun toolFailure_isRecordedWithItsCode() = runBlocking {
        generateBehavior = { throw ToolException("document_not_found") }
        val failed = engine().run(agentTask())
        assertEquals("document_not_found", failed.step("generate")!!.error)
    }

    @Test
    fun restart_sideEffectStepWithFoundResult_isNotRepeated() = runBlocking {
        reconciled = mapOf("reply" to "", "documentId" to "doc-saved")
        val e = engine()
        val task = agentTask()
        val interrupted = task.withStep(task.step("read_1")!!.copy(status = StepStatus.COMPLETED, output = mapOf("text" to "s")))
            .withStep(task.step("generate")!!.copy(status = StepStatus.RUNNING, startedAt = 1L))
            .copy(status = TaskStatus.RUNNING)
        val recovered = e.recover(interrupted)
        assertEquals(TaskStatus.PAUSED, recovered.status)
        assertEquals("doc-saved", recovered.step("generate")!!.output["documentId"])
        val done = e.run(recovered)
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertTrue("generate" !in runs) // the document is not created twice
    }

    @Test
    fun restart_sideEffectWithUnknownResult_needsApprovalBeforeRunningAgain() = runBlocking {
        reconciled = null
        val e = engine()
        val task = agentTask()
        val interrupted = task.withStep(task.step("read_1")!!.copy(status = StepStatus.RUNNING))
            .withStep(task.step("generate")!!.copy(status = StepStatus.PENDING))
            .copy(status = TaskStatus.RUNNING)
        // A read-only step is simply run again.
        assertEquals(StepStatus.PENDING, e.recover(interrupted).step("read_1")!!.status)

        val midGenerate = task.withStep(task.step("read_1")!!.copy(status = StepStatus.COMPLETED))
            .withStep(task.step("generate")!!.copy(status = StepStatus.RUNNING, startedAt = 1L))
            .copy(status = TaskStatus.RUNNING)
        val recovered = e.recover(midGenerate)
        assertEquals(StepStatus.UNKNOWN, recovered.step("generate")!!.status)
        val waiting = e.run(recovered)
        assertEquals(TaskStatus.WAITING_FOR_APPROVAL, waiting.status)
        assertTrue("generate" !in runs)
        val done = e.run(e.decide(waiting, "generate", approved = true))
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals(1, runs.count { it == "generate" })
    }

    @Test
    fun invalidStep_isRefusedBeforeAnythingRuns() = runBlocking {
        val task = agentTask().let { t -> t.withStep(t.step("read_1")!!.copy(input = mapOf("fileId" to "library:d1", "command" to "rm -rf /"))) }
        val failed = engine().run(task)
        assertEquals(TaskStatus.FAILED, failed.status)
        assertEquals("unexpected_input", failed.error)
        assertTrue(runs.isEmpty())
    }

    // ------------------------------------------------------------------ persistence

    @Test
    fun codec_roundTripsEveryField_andRejectsGarbage() {
        val task = agentTask(share = true).copy(
            status = TaskStatus.WAITING_FOR_APPROVAL,
            currentStepId = "share",
            warnings = listOf("issue:TEMPLATE_NOT_APPLIED"),
            error = null,
            attempts = listOf(
                ProviderAttempt("p1", "OpenAI", "gpt", 1, AiErrorKind.TIMEOUT, FailoverDecision.NEXT),
                ProviderAttempt("p2", "Claude", "claude", 1, null, null)
            ),
            updatedAt = 1_800_000_000_123L
        ).let { t -> t.withStep(t.step("generate")!!.copy(status = StepStatus.COMPLETED, output = mapOf("reply" to "نص عربي", "documentId" to "doc"), startedAt = 1_800_000_000_001L)) }
        assertEquals(task, AgentTaskCodec.decode(AgentTaskCodec.encode(task)))
        assertNull(AgentTaskCodec.decode("not json"))
        assertNull(AgentTaskCodec.decode("""{"id":"x"}"""))
        // No credentials are part of the record.
        assertTrue(!AgentTaskCodec.encode(task).contains("apiKey", ignoreCase = true))
    }

    // ------------------------------------------------------------------ validation

    @Test
    fun validator_distinguishesCompletedWarningsIncompleteAndFailed() {
        val good = ProducedDocument("DOCX", true, 2048, 500, templateApplied = true)
        assertEquals(ValidationOutcome.COMPLETED, ResultValidator.validate("DOCX", "", good).outcome)
        assertEquals(ValidationOutcome.COMPLETED_WITH_WARNINGS, ResultValidator.validate("DOCX", "", good.copy(templateApplied = false), templateRequested = true).outcome)
        assertEquals(listOf(ValidationIssue.MISSING_SECTIONS), ResultValidator.validate("DOCX", "", good, "Intro only", requiredSections = listOf("Conclusion")).issues)
        assertEquals(ValidationOutcome.INCOMPLETE, ResultValidator.validate("PPTX", "", good).outcome)
        assertEquals(ValidationOutcome.INCOMPLETE, ResultValidator.validate("DOCX", "", good.copy(textLength = 3)).outcome)
        assertEquals(ValidationOutcome.FAILED, ResultValidator.validate("DOCX", "reply", null).outcome)
        assertEquals(ValidationOutcome.FAILED, ResultValidator.validate("DOCX", "", good.copy(fileExists = false)).outcome)
        assertEquals(ValidationOutcome.FAILED, ResultValidator.validate("DOCX", "", good.copy(fileSize = 0)).outcome)
        assertEquals(ValidationOutcome.COMPLETED, ResultValidator.validate(null, "An answer", null).outcome)
        assertEquals(ValidationOutcome.FAILED, ResultValidator.validate(null, " ", null).outcome)
    }

    @Test
    fun tableStats_computesFiguresDeterministically() {
        val text = "Region\tQ1\tQ2\nNorth\t1,200.50\t300\nSouth\t799.50\t700\nWest\t1000\t—"
        val stats = TableStats.columns(text)
        assertEquals(listOf("Q1", "Q2"), stats.map { it.name })
        assertEquals(3000.0, stats[0].sum, 1e-9)
        assertEquals(1000.0, stats[0].mean, 1e-9)
        assertEquals(1000.0, stats[1].sum, 1e-9)
        assertEquals(2, stats[1].count)
        assertEquals("Q1: count=3, sum=3000, mean=1000, min=799.50, max=1200.50", TableStats.describe(stats).lines().first())
        assertEquals(1234.5, TableStats.parseNumber("1 234,5")!!, 1e-9)
        assertNull(TableStats.parseNumber("abc"))
        assertTrue(TableStats.columns("just prose\nwithout a table").isEmpty())
    }

    @Test
    fun aTaskHasNoMoreThanOnePendingApprovalAtATime() {
        val t = agentTask(share = true)
        assertNotNull(t.step("share"))
        assertNull(t.pendingApproval) // waiting is set by the engine, not by the planner
    }
}
