package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.agent.RoutingStatus
import com.example.agent.core.AgentStep
import com.example.agent.core.AgentTask
import com.example.agent.core.StepStatus
import com.example.agent.core.TaskPlanner
import com.example.agent.core.TaskStatus
import com.example.agent.thesis.ThesisTools
import com.example.agent.routing.FailoverDecision
import com.example.agent.routing.RoutingEvent
import com.example.agent.routing.SkipReason
import com.example.ui.aiErrorStringRes

/**
 * Agent status above the message composer: the model in use and any provider switch, and for an
 * Agent Mode task its plan, progress and controls (Pause, Resume, Cancel, Approve, Reject).
 * Quick Mode requests show only the routing line, and only while a switch or pause happened.
 * Height is bounded (it scrolls inside) so messages and the composer stay usable on short screens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentPanel(
    task: AgentTask?,
    routing: RoutingStatus?,
    sourceTitles: Map<String, String>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onPreviewFile: (String) -> Unit = {},
    onRevokeFile: (String) -> Unit = {},
    /** Thesis tasks: open the changed section in Thesis Builder, undo the change. */
    onOpenThesis: () -> Unit = {},
    onUndoThesis: () -> Unit = {}
) {
    val routingWorthShowing = routing != null && (routing.events.isNotEmpty() || routing.waitingMs != null)
    if (task == null && !routingWorthShowing) return
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.4f).dp
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth().testTag("agent_panel")
    ) {
        Column(
            Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (routing != null) RoutingLine(routing)
            if (task != null) {
                TaskCard(task, sourceTitles, onPause, onResume, onCancel, onApprove, onReject, onDismiss, onOpenThesis, onUndoThesis)
                TaskFilesSection(task.files, onPreviewFile, onRevokeFile)
            }
        }
    }
}

@Composable
private fun RoutingLine(routing: RoutingStatus) {
    Column(Modifier.testTag("agent_routing")) {
        if (routing.providerName != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.agent_model_line, routing.providerName, routing.model.orEmpty()),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("agent_current_model")
                )
            }
        }
        routing.events.forEach { e ->
            val text = when (e) {
                is RoutingEvent.Failed -> stringResource(
                    if (e.decision == FailoverDecision.STOP) R.string.agent_event_stopped else R.string.agent_event_failed,
                    e.candidate.name, stringResource(aiErrorStringRes(e.kind))
                )
                is RoutingEvent.Skipped -> stringResource(R.string.agent_event_skipped, e.candidate.name, stringResource(skipReasonRes(e.reason)))
                is RoutingEvent.Waiting -> stringResource(R.string.agent_waiting, ((e.delayMs + 999) / 1000).toInt(), e.nextCycle)
                else -> null
            }
            if (text != null) Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("agent_routing_event"))
        }
    }
}

fun skipReasonRes(reason: SkipReason): Int = when (reason) {
    SkipReason.DISABLED -> R.string.agent_skip_disabled
    SkipReason.NO_STRUCTURED_OUTPUT -> R.string.agent_skip_structured
    SkipReason.NO_IMAGE_INPUT -> R.string.agent_skip_image
    SkipReason.NO_TOOL_CALLING -> R.string.agent_skip_tools
    SkipReason.CONTEXT_TOO_SMALL -> R.string.agent_skip_context
    SkipReason.EXCLUDED_EARLIER -> R.string.agent_skip_excluded
}

fun taskStatusRes(status: TaskStatus): Int = when (status) {
    TaskStatus.PENDING, TaskStatus.PLANNING -> R.string.agent_status_planning
    TaskStatus.RUNNING -> R.string.agent_status_running
    TaskStatus.WAITING_FOR_APPROVAL -> R.string.agent_status_waiting
    TaskStatus.PAUSED -> R.string.agent_status_paused
    TaskStatus.COMPLETED -> R.string.agent_status_completed
    TaskStatus.COMPLETED_WITH_WARNINGS -> R.string.agent_status_warnings
    TaskStatus.INCOMPLETE -> R.string.agent_status_incomplete
    TaskStatus.FAILED -> R.string.agent_status_failed
    TaskStatus.CANCELLED -> R.string.agent_status_cancelled
}

private fun stepStatusRes(status: StepStatus): Int = when (status) {
    StepStatus.PENDING -> R.string.agent_step_pending
    StepStatus.RUNNING -> R.string.agent_step_running
    StepStatus.WAITING_FOR_APPROVAL -> R.string.agent_step_waiting
    StepStatus.COMPLETED -> R.string.agent_step_done
    StepStatus.FAILED -> R.string.agent_step_failed
    StepStatus.CANCELLED -> R.string.agent_step_cancelled
    StepStatus.REJECTED -> R.string.agent_step_rejected
    StepStatus.UNKNOWN -> R.string.agent_step_unknown
}

@Composable
private fun stepTitle(step: AgentStep, sourceTitles: Map<String, String>): String = when (step.toolId) {
    TaskPlanner.Tools.LIBRARY_READ, TaskPlanner.Tools.FILE_READ -> stringResource(
        R.string.agent_tool_read,
        step.output["title"] ?: sourceTitles[step.input["documentId"]] ?: step.input["fileId"]?.let { id -> sourceTitles[id.substringAfter(':')] } ?: "…"
    )
    TaskPlanner.Tools.TEMPLATE_INSPECT -> stringResource(R.string.agent_tool_inspect_template)
    TaskPlanner.Tools.DOCX_PROPOSE_EDITS -> stringResource(R.string.agent_tool_propose_edits)
    TaskPlanner.Tools.DOCX_WRITE_COPY -> stringResource(R.string.agent_tool_write_copy)
    TaskPlanner.Tools.FILE_REPLACE_ORIGINAL -> stringResource(R.string.agent_tool_replace_original)
    TaskPlanner.Tools.CONTENT_GENERATE -> step.input["format"]?.let { stringResource(R.string.agent_tool_generate_format, it) }
        ?: stringResource(R.string.agent_tool_generate)
    TaskPlanner.Tools.RESULT_VALIDATE -> stringResource(R.string.agent_tool_validate)
    TaskPlanner.Tools.DOCUMENT_SHARE -> stringResource(R.string.agent_tool_share)
    ThesisTools.LOCATE -> stringResource(R.string.agent_tool_thesis_locate)
    ThesisTools.SOURCE_EXTRACT -> stringResource(R.string.agent_tool_source_extract,
        step.output["title"] ?: step.input["fileId"]?.let { id -> sourceTitles[id.substringAfter(':')] } ?: "…")
    ThesisTools.PREPARE -> stringResource(R.string.agent_tool_thesis_prepare)
    ThesisTools.APPLY -> stringResource(R.string.agent_tool_thesis_apply)
    ThesisTools.VALIDATE -> stringResource(R.string.agent_tool_thesis_validate)
    else -> step.toolId
}

/** What the user approves for a thesis change: target, position, source, content preview and warnings. */
@Composable
private fun ThesisApprovalDetails(task: AgentTask) {
    val located = task.step("locate")?.output.orEmpty()
    val extract = task.step("extract")?.output.orEmpty()
    val prepared = task.step("prepare")?.output.orEmpty()
    val small = MaterialTheme.typography.bodySmall
    Column(Modifier.testTag("agent_thesis_preview"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val position = prepared["position"].orEmpty()
        Text(
            when {
                position.startsWith("replace:") -> stringResource(R.string.agent_thesis_position_replace, position.substringAfter(':').toIntOrNull()?.let { if (it == -1) stringResource(R.string.agent_thesis_last) else it.toString() } ?: "?")
                position == "start" -> stringResource(R.string.agent_thesis_position_start)
                else -> stringResource(R.string.agent_thesis_position_end, position.substringAfter(':').toIntOrNull() ?: 0)
            },
            style = small, modifier = Modifier.testTag("agent_thesis_position")
        )
        if (extract["title"] != null) Text(stringResource(R.string.agent_thesis_source, extract["title"].orEmpty(), extract["pages"].orEmpty()), style = small)
        prepared["rows"]?.let { r -> Text(stringResource(R.string.agent_thesis_table_shape, r.toIntOrNull() ?: 0, prepared["columns"]?.toIntOrNull() ?: 0), style = small) }
        prepared["oldText"]?.let { old ->
            Text(stringResource(R.string.agent_thesis_old_text), style = small, fontWeight = FontWeight.SemiBold)
            Text(old, style = small, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("agent_thesis_old"))
            Text(stringResource(R.string.agent_thesis_new_text), style = small, fontWeight = FontWeight.SemiBold)
        }
        prepared["preview"]?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = small, maxLines = 12, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("agent_thesis_content"))
        }
        val warn = MaterialTheme.colorScheme.error
        if (located["active"] == "false") Text(stringResource(R.string.agent_thesis_warn_not_active), style = small, color = warn, modifier = Modifier.testTag("agent_thesis_not_active"))
        if (extract["uncertain"] == "true") Text(stringResource(R.string.agent_thesis_warn_uncertain), style = small, color = warn)
        if (extract["tableUncertain"] == "true") Text(stringResource(R.string.agent_thesis_warn_table_uncertain), style = small, color = warn)
        task.step("locate")?.input?.get("pageHint")?.toIntOrNull()?.let { page ->
            Text(stringResource(R.string.agent_thesis_page_hint, located["sectionTitle"].orEmpty(), page), style = small)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(
    task: AgentTask,
    sourceTitles: Map<String, String>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
    onOpenThesis: () -> Unit,
    onUndoThesis: () -> Unit
) {
    Column(Modifier.testTag("agent_task"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.agent_task_title),
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(taskStatusRes(task.status)),
                style = MaterialTheme.typography.labelMedium,
                color = if (task.status == TaskStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("agent_task_status")
            )
        }
        val total = task.steps.size
        Text(
            stringResource(R.string.agent_progress, task.completedSteps, total),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("agent_progress")
        )
        if (task.status == TaskStatus.RUNNING) {
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else task.completedSteps / total.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )
        }
        task.steps.forEachIndexed { i, step ->
            val statusText = stringResource(stepStatusRes(step.status))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("agent_step_${step.id}").semantics(mergeDescendants = true) {
                    contentDescription = statusText
                }
            ) {
                Icon(
                    imageVector = when (step.status) {
                        StepStatus.COMPLETED -> Icons.Default.CheckCircle
                        StepStatus.RUNNING -> Icons.Default.Sync
                        StepStatus.WAITING_FOR_APPROVAL, StepStatus.UNKNOWN -> Icons.Default.HourglassEmpty
                        StepStatus.FAILED, StepStatus.REJECTED, StepStatus.CANCELLED -> Icons.Default.Error
                        StepStatus.PENDING -> if (task.status == TaskStatus.PAUSED) Icons.Default.PauseCircle else Icons.Default.RadioButtonUnchecked
                    },
                    contentDescription = null,
                    tint = if (step.status == StepStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("${i + 1}. ${stepTitle(step, sourceTitles)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        task.pendingApproval?.let { step ->
            val target = step.input["fileId"]?.let { task.file(it) }
            val proposal = task.step("propose")?.output.orEmpty()
            val text = when {
                step.error == "completion_unknown" -> stringResource(R.string.agent_approval_unknown)
                step.toolId == TaskPlanner.Tools.DOCX_WRITE_COPY -> stringResource(
                    R.string.agent_approval_edit_copy,
                    target?.name ?: proposal["title"].orEmpty(),
                    proposal["changed"]?.toIntOrNull() ?: 0
                )
                step.toolId == TaskPlanner.Tools.FILE_REPLACE_ORIGINAL -> stringResource(R.string.agent_approval_replace, target?.name.orEmpty())
                step.toolId == ThesisTools.APPLY -> task.step("locate")?.output.orEmpty().let {
                    stringResource(R.string.agent_thesis_approval, it["thesisTitle"].orEmpty(), it["sectionTitle"].orEmpty())
                }
                else -> stringResource(R.string.agent_approval_share)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("agent_approval_prompt"))
            if (step.toolId == ThesisTools.APPLY && step.error != "completion_unknown") ThesisApprovalDetails(task)
            if (step.toolId == TaskPlanner.Tools.DOCX_WRITE_COPY) {
                proposal["examples"]?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 12, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("agent_edit_examples"))
                }
                if ((proposal["mixedFormatting"]?.toIntOrNull() ?: 0) > 0) {
                    Text(stringResource(R.string.agent_edit_formatting_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (task.status) {
                TaskStatus.RUNNING -> {
                    OutlinedButton(onClick = onPause, modifier = Modifier.testTag("agent_pause")) { Text(stringResource(R.string.agent_pause)) }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("agent_cancel")) { Text(stringResource(R.string.agent_cancel)) }
                }
                TaskStatus.PAUSED, TaskStatus.PENDING, TaskStatus.PLANNING -> {
                    Button(onClick = onResume, modifier = Modifier.testTag("agent_resume")) { Text(stringResource(R.string.agent_resume)) }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("agent_cancel")) { Text(stringResource(R.string.agent_cancel)) }
                }
                TaskStatus.WAITING_FOR_APPROVAL -> {
                    Button(onClick = onApprove, modifier = Modifier.testTag("agent_approve")) { Text(stringResource(R.string.agent_approve)) }
                    OutlinedButton(onClick = onReject, modifier = Modifier.testTag("agent_reject")) { Text(stringResource(R.string.agent_reject)) }
                    TextButton(onClick = onCancel, modifier = Modifier.testTag("agent_cancel")) { Text(stringResource(R.string.agent_cancel)) }
                }
                else -> {
                    // A thesis change that was applied and checked: open it, or undo it (once).
                    val applied = task.step("apply")?.status == StepStatus.COMPLETED && task.warnings.none { it.startsWith("undone:") } &&
                        (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.COMPLETED_WITH_WARNINGS)
                    if (applied) {
                        OutlinedButton(onClick = onOpenThesis, modifier = Modifier.testTag("agent_thesis_open")) { Text(stringResource(R.string.agent_thesis_open)) }
                        OutlinedButton(onClick = onUndoThesis, modifier = Modifier.testTag("agent_thesis_undo")) { Text(stringResource(R.string.agent_thesis_undo)) }
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("agent_dismiss")) { Text(stringResource(R.string.agent_dismiss)) }
                }
            }
        }
    }
}
