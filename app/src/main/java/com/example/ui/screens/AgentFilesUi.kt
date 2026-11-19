package com.example.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.agent.core.FileRole
import com.example.agent.core.FileSource
import com.example.agent.core.LibraryRef
import com.example.agent.core.TaskFile
import com.example.agent.files.ImportOutcome
import com.example.agent.files.ImportStatus

/** MIME types the phone file picker offers: only formats the app can read. */
val IMPORT_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "image/png", "image/jpeg", "image/webp"
)

fun importStatusRes(status: ImportStatus): Int = when (status) {
    ImportStatus.IMPORTED -> R.string.import_status_imported
    ImportStatus.DUPLICATE -> R.string.import_status_duplicate
    ImportStatus.UNSUPPORTED -> R.string.import_status_unsupported
    ImportStatus.LEGACY_OR_ENCRYPTED_OFFICE -> R.string.import_status_legacy
    ImportStatus.ENCRYPTED_PDF -> R.string.import_status_encrypted_pdf
    ImportStatus.DAMAGED -> R.string.import_status_damaged
    ImportStatus.TOO_LARGE -> R.string.import_status_too_large
    ImportStatus.UNREADABLE -> R.string.import_status_unreadable
    ImportStatus.NO_SPACE -> R.string.import_status_no_space
    ImportStatus.CANCELLED -> R.string.import_status_cancelled
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    else -> "${(bytes + 1023) / 1024} KB"
}

/** Result of "Import files from phone": name, type, size and status of each picked file. */
@Composable
fun ImportResultsDialog(results: List<ImportOutcome>, onDismiss: () -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("import_results"),
        title = { Text(stringResource(R.string.import_results_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = maxHeight)) {
                items(results) { r ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("import_result")) {
                        Text(r.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(r.kind?.fileType, sizeText(r.sizeBytes)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            stringResource(importStatusRes(r.status)),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (r.status == ImportStatus.IMPORTED || r.status == ImportStatus.DUPLICATE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("import_results_ok")) { Text(stringResource(R.string.action_ok)) } }
    )
}

@Composable
private fun sourceLabel(source: FileSource) = stringResource(
    when (source) {
        FileSource.LIBRARY -> R.string.agent_source_library
        FileSource.MY_TEMPLATE -> R.string.agent_source_template
        FileSource.DEVICE -> R.string.agent_source_device
    }
)

/**
 * Agent file picker: import from the phone, or choose Library documents and My Templates
 * templates (sources shown separately). Chosen files are attached to the next request only.
 */
@Composable
fun AgentFilePickerDialog(
    files: List<LibraryRef>,
    selected: List<LibraryRef>,
    onFromPhone: () -> Unit,
    onToggle: (LibraryRef) -> Unit,
    onPreview: (LibraryRef) -> Unit,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableStateOf(FileSource.LIBRARY) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("agent_file_picker"),
        title = { Text(stringResource(R.string.agent_files_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onFromPhone, modifier = Modifier.fillMaxWidth().testTag("agent_files_from_phone")) {
                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.agent_files_from_phone))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tab == FileSource.LIBRARY, onClick = { tab = FileSource.LIBRARY },
                        label = { Text(stringResource(R.string.agent_source_library)) }, modifier = Modifier.testTag("agent_files_tab_library"),
                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) })
                    FilterChip(selected = tab == FileSource.MY_TEMPLATE, onClick = { tab = FileSource.MY_TEMPLATE },
                        label = { Text(stringResource(R.string.agent_source_template)) }, modifier = Modifier.testTag("agent_files_tab_templates"),
                        leadingIcon = { Icon(Icons.Default.Style, contentDescription = null, modifier = Modifier.size(16.dp)) })
                }
                val shown = files.filter { it.source == tab }
                if (shown.isEmpty()) Text(stringResource(R.string.agent_files_empty), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = maxHeight)) {
                    items(shown, key = { it.fileId }) { f ->
                        val isSelected = selected.any { it.fileId == f.fileId }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { onToggle(f) }.padding(vertical = 6.dp).testTag("agent_file_${f.fileId}")
                        ) {
                            androidx.compose.material3.Checkbox(checked = isSelected, onCheckedChange = { onToggle(f) })
                            Column(Modifier.weight(1f)) {
                                Text(f.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(f.fileType, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onPreview(f) }) { Text(stringResource(R.string.agent_file_preview)) }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss, modifier = Modifier.testTag("agent_files_done")) { Text(stringResource(R.string.agent_files_done)) } }
    )
}

/** Files attached to the next request, with their source; tap ✕ to remove. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectedFilesRow(selected: List<LibraryRef>, onRemove: (String) -> Unit) {
    if (selected.isEmpty()) return
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("agent_selected_files"),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        selected.forEach { f ->
            InputChip(
                selected = true,
                onClick = { onRemove(f.fileId) },
                label = { Text("${f.title} · ${sourceLabel(f.source)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(if (f.source == FileSource.LIBRARY) Icons.Default.Description else Icons.Default.Style, contentDescription = null, modifier = Modifier.size(16.dp)) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.agent_file_remove, f.title), modifier = Modifier.size(16.dp)) },
                modifier = Modifier.testTag("agent_selected_${f.fileId}")
            )
        }
    }
}

/** "Which file did you mean?" Nothing is read until the user picks one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileChoiceCard(candidates: List<LibraryRef>, onPick: (LibraryRef) -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("agent_file_choice"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.agent_choose_file), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            candidates.forEach { f ->
                AssistChip(
                    onClick = { onPick(f) },
                    label = { Text("${f.title} · ${sourceLabel(f.source)}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.testTag("agent_choice_${f.fileId}")
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("agent_choice_cancel")) { Text(stringResource(R.string.agent_cancel)) }
        }
    }
}

/** Files the shown task uses: role, source, preview and revoke. */
@Composable
fun TaskFilesSection(files: List<TaskFile>, onPreview: (String) -> Unit, onRevoke: (String) -> Unit) {
    if (files.isEmpty()) return
    Column(Modifier.testTag("agent_task_files")) {
        Text(stringResource(R.string.agent_task_files), style = MaterialTheme.typography.labelLarge)
        files.forEach { f ->
            val role = stringResource(
                when (f.role) {
                    FileRole.SOURCE -> R.string.agent_role_source
                    FileRole.TEMPLATE -> R.string.agent_role_template
                    FileRole.EDIT_TARGET -> R.string.agent_role_edit
                }
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("agent_task_file_${f.id}")) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "$role · ${sourceLabel(f.source)}" + if (!f.granted) " · " + stringResource(R.string.agent_access_revoked) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (f.granted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                }
                TextButton(onClick = { onPreview(f.id) }) { Text(stringResource(R.string.agent_file_preview)) }
                if (f.granted) TextButton(onClick = { onRevoke(f.id) }, modifier = Modifier.testTag("agent_revoke_${f.id}")) {
                    Text(stringResource(R.string.agent_revoke))
                }
            }
        }
    }
}
