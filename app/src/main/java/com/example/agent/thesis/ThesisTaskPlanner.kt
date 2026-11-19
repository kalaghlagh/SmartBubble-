package com.example.agent.thesis

import com.example.agent.core.AgentMode
import com.example.agent.core.AgentStep
import com.example.agent.core.AgentTask
import com.example.agent.core.ApprovalState
import com.example.agent.core.FileRole
import com.example.agent.core.LibraryRef
import com.example.agent.core.TaskFile
import com.example.agent.core.TaskStatus

/**
 * Plans a thesis document request as a visible, ordered task. Every step is a local tool: no AI
 * request is made, so the workflow also works offline. The only step that changes anything is
 * [ThesisTools.APPLY], which waits for the user's approval of the exact prepared change.
 *
 * Insert from a source:  locate -> extract -> prepare -> apply (approval) -> validate
 * Text from the message: locate -> prepare -> apply (approval) -> validate
 * Extract only:          extract (read-only, the result is shown in the conversation)
 */
object ThesisTaskPlanner {
    fun plan(
        taskId: String,
        sessionId: String,
        request: String,
        req: ThesisRequest,
        source: LibraryRef?,
        language: String,
        now: Long,
        newId: () -> String
    ): AgentTask {
        val steps = ArrayList<AgentStep>()
        val needsSource = req.kind == ThesisEditKind.INSERT_PAGE_TEXT || req.kind == ThesisEditKind.INSERT_TABLE || req.kind == ThesisEditKind.EXTRACT_ONLY
        require(!needsSource || source != null) { "source required" }
        val extractInput = buildMap {
            source?.let { put("fileId", it.fileId) }
            if (req.pages.isNotEmpty()) put("pages", req.pages.joinToString(","))
            req.tableNumber?.let { put("tableNumber", it) }
        }
        if (req.kind == ThesisEditKind.EXTRACT_ONLY) {
            steps += AgentStep(id = "extract", toolId = ThesisTools.SOURCE_EXTRACT, input = extractInput, executionId = newId())
        } else {
            steps += AgentStep(
                id = "locate", toolId = ThesisTools.LOCATE,
                input = buildMap {
                    put("kind", req.kind.name)
                    req.section?.kind?.let { put("sectionKind", it.name) }
                    req.section?.chapterNumber?.let { put("chapter", it.toString()) }
                    req.section?.title?.let { put("sectionTitle", it) }
                    req.paragraph?.let { put("paragraph", it.toString()) }
                    req.thesisTitle?.let { put("thesisTitle", it) }
                    req.pageHint?.let { put("pageHint", it.toString()) }
                    if (req.atStart) put("atStart", "true")
                },
                executionId = newId()
            )
            if (needsSource) steps += AgentStep(id = "extract", toolId = ThesisTools.SOURCE_EXTRACT, input = extractInput, executionId = newId())
            steps += AgentStep(
                id = "prepare", toolId = ThesisTools.PREPARE,
                input = buildMap {
                    put("kind", req.kind.name)
                    req.text?.let { put("text", it) }
                    req.paragraph?.let { put("paragraph", it.toString()) }
                    if (req.atStart) put("atStart", "true")
                    req.tableNumber?.let { put("tableNumber", it) }
                },
                dependsOn = listOf("locate") + if (needsSource) listOf("extract") else emptyList(),
                executionId = newId()
            )
            steps += AgentStep(id = "apply", toolId = ThesisTools.APPLY, dependsOn = listOf("prepare"), approval = ApprovalState.PENDING, executionId = newId())
            steps += AgentStep(id = "check", toolId = ThesisTools.VALIDATE, dependsOn = listOf("apply"), executionId = newId())
        }
        return AgentTask(
            id = taskId, sessionId = sessionId, request = request, createdAt = now, mode = AgentMode.AGENT,
            status = TaskStatus.PENDING, steps = steps, language = language,
            files = listOfNotNull(source?.let { TaskFile(it.fileId, it.source, it.title, it.fileType, FileRole.SOURCE) })
        )
    }

    /** True when [task] is a thesis document task (it has thesis or extraction steps). */
    fun isThesisTask(task: AgentTask): Boolean = task.steps.any { it.toolId == ThesisTools.LOCATE || it.toolId == ThesisTools.SOURCE_EXTRACT }
}
