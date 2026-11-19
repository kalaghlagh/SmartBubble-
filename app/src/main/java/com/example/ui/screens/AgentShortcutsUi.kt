package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.agent.AgentWorkflow
import com.example.agent.core.LibraryRef

/** Document types a source document can be (picked from the phone): PDF, Word, images. */
val SOURCE_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "image/png", "image/jpeg", "image/webp"
)

private data class Shortcut(val workflow: AgentWorkflow, val icon: ImageVector, val title: Int, val description: Int, val tag: String)

private val shortcuts = listOf(
    Shortcut(AgentWorkflow.REFORMAT, Icons.Default.EditNote, R.string.agent_shortcut_reformat, R.string.agent_shortcut_reformat_desc, "agent_shortcut_reformat"),
    Shortcut(AgentWorkflow.THESIS, Icons.Default.School, R.string.agent_shortcut_thesis, R.string.agent_shortcut_thesis_desc, "agent_shortcut_thesis"),
    Shortcut(AgentWorkflow.TEMPLATE, Icons.Default.Style, R.string.agent_shortcut_template, R.string.agent_shortcut_template_desc, "agent_shortcut_template")
)

/**
 * The empty conversation: the Agent's name, what it can do, and exactly three shortcuts. Cards
 * stack on narrow screens and sit side by side from 560 dp; texts wrap (never cut) at large font
 * sizes and every card is at least 72 dp high.
 */
@Composable
fun AgentWelcome(onShortcut: (AgentWorkflow) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("agent_welcome"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.agent_welcome_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        Text(stringResource(R.string.agent_welcome_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 560.dp) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    shortcuts.forEach { s -> ShortcutCard(s, onShortcut, Modifier.weight(1f).fillMaxHeight()) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    shortcuts.forEach { s -> ShortcutCard(s, onShortcut, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

@Composable
private fun ShortcutCard(s: Shortcut, onShortcut: (AgentWorkflow) -> Unit, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = modifier
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button) { onShortcut(s.workflow) }
            .testTag(s.tag)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).padding(0.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(s.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(s.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(s.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The shortcut in use, above the message box: its name, the source document for Document
 * Reformatting (with a button to choose or change it), and a close button that ends it.
 */
@Composable
fun AgentWorkflowBar(
    workflow: AgentWorkflow,
    source: LibraryRef?,
    onChooseSource: () -> Unit,
    onChooseTemplate: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (icon, title) = when (workflow) {
        AgentWorkflow.REFORMAT -> Icons.Default.EditNote to R.string.agent_shortcut_reformat
        AgentWorkflow.THESIS -> Icons.Default.School to R.string.agent_shortcut_thesis
        AgentWorkflow.TEMPLATE -> Icons.Default.Style to R.string.agent_shortcut_template
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f), modifier = modifier.fillMaxWidth().testTag("agent_workflow_bar")) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (workflow == AgentWorkflow.REFORMAT && source != null) {
                    Text(source.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("agent_workflow_source"))
                }
            }
            when (workflow) {
                AgentWorkflow.REFORMAT -> TextButton(onClick = onChooseSource, modifier = Modifier.heightIn(min = 48.dp).testTag("agent_workflow_choose_source")) {
                    Text(stringResource(if (source == null) R.string.agent_source_choose else R.string.agent_source_change), maxLines = 2)
                }
                AgentWorkflow.TEMPLATE -> TextButton(onClick = onChooseTemplate, modifier = Modifier.heightIn(min = 48.dp).testTag("agent_workflow_choose_template")) {
                    Text(stringResource(R.string.chat_select_template), maxLines = 2)
                }
                AgentWorkflow.THESIS -> Unit
            }
            IconButton(onClick = onClose, modifier = Modifier.testTag("agent_workflow_close")) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.agent_workflow_end))
            }
        }
    }
}

/**
 * Picks the SOURCE document for Document Reformatting: from the phone (Storage Access Framework)
 * or from the Library. Templates are not listed here: they are chosen with the template picker.
 */
@Composable
fun SourceDocumentPicker(
    files: List<LibraryRef>,
    onFromDevice: () -> Unit,
    onPick: (LibraryRef) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("source_picker"),
        title = { Text(stringResource(R.string.agent_source_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.agent_source_help), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onFromDevice, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("source_from_device")) {
                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.agent_source_from_device))
                }
                Text(stringResource(R.string.agent_source_from_library), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                if (files.isEmpty()) {
                    Text(stringResource(R.string.agent_source_library_empty), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("source_library_empty"))
                } else {
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(files, key = { it.fileId }) { f ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                                    .clickable(role = Role.Button) { onPick(f) }.padding(horizontal = 8.dp).testTag("source_library_${f.id}"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(f.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(f.fileType, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("source_picker_cancel")) { Text(stringResource(R.string.agent_cancel)) } }
    )
}
